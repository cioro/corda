package net.corda.nodeapi.internal.serialization

import com.esotericsoftware.kryo.*
import com.esotericsoftware.kryo.factories.ReflectionSerializerFactory
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import com.esotericsoftware.kryo.serializers.FieldSerializer
import com.esotericsoftware.kryo.util.MapReferenceResolver
import net.corda.core.contracts.PrivacySalt
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.Party
import net.corda.core.serialization.SerializeAsTokenContext
import net.corda.core.serialization.SerializedBytes
import net.corda.core.transactions.*
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sun.security.ec.ECPublicKeyImpl
import sun.security.util.DerValue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertPath
import java.security.cert.CertificateFactory
import java.util.*
import javax.annotation.concurrent.ThreadSafe
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType

/**
 * Serialization utilities, using the Kryo framework with a custom serialiser for immutable data classes and a dead
 * simple, totally non-extensible binary (sub)format.
 *
 * This is NOT what should be used in any final platform product, rather, the final state should be a precisely
 * specified and standardised binary format with attention paid to anti-malleability, versioning and performance.
 * FIX SBE is a potential candidate: it prioritises performance over convenience and was designed for HFT. Google
 * Protocol Buffers with a minor tightening to make field reordering illegal is another possibility.
 *
 * FIX SBE:
 *     https://real-logic.github.io/simple-binary-encoding/
 *     http://mechanical-sympathy.blogspot.co.at/2014/05/simple-binary-encoding.html
 * Protocol buffers:
 *     https://developers.google.com/protocol-buffers/
 *
 * But for now we use Kryo to maximise prototyping speed.
 *
 * Note that this code ignores *ALL* concerns beyond convenience, in particular it ignores:
 *
 * - Performance
 * - Security
 *
 * This code will happily deserialise literally anything, including malicious streams that would reconstruct classes
 * in invalid states, thus violating system invariants. It isn't designed to handle malicious streams and therefore,
 * isn't usable beyond the prototyping stage. But that's fine: we can revisit serialisation technologies later after
 * a formal evaluation process.
 *
 * We now distinguish between internal, storage related Kryo and external, network facing Kryo.  We presently use
 * some non-whitelisted classes as part of internal storage.
 * TODO: eliminate internal, storage related whitelist issues, such as private keys in blob storage.
 */

/**
 * A serialiser that avoids writing the wrapper class to the byte stream, thus ensuring [SerializedBytes] is a pure
 * type safety hack.
 */
object SerializedBytesSerializer : Serializer<SerializedBytes<Any>>() {
    override fun write(kryo: Kryo, output: Output, obj: SerializedBytes<Any>) {
        output.writeVarInt(obj.bytes.size, true)
        output.writeBytes(obj.bytes)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<SerializedBytes<Any>>): SerializedBytes<Any> {
        return SerializedBytes(input.readBytes(input.readVarInt(true)))
    }
}

/**
 * Serializes properties and deserializes by using the constructor. This assumes that all backed properties are
 * set via the constructor and the class is immutable.
 */
class ImmutableClassSerializer<T : Any>(val klass: KClass<T>) : Serializer<T>() {
    val props = klass.memberProperties.sortedBy { it.name }
    val propsByName = props.associateBy { it.name }
    val constructor = klass.primaryConstructor!!

    init {
        // Verify that this class is immutable (all properties are final)
        assert(props.none { it is KMutableProperty<*> })
    }

    // Just a utility to help us catch cases where nodes are running out of sync versions.
    private fun hashParameters(params: List<KParameter>): Int {
        return params.map {
            (it.name ?: "") + it.index.toString() + it.type.javaType.typeName
        }.hashCode()
    }

    override fun write(kryo: Kryo, output: Output, obj: T) {
        output.writeVarInt(constructor.parameters.size, true)
        output.writeInt(hashParameters(constructor.parameters))
        for (param in constructor.parameters) {
            val kProperty = propsByName[param.name!!]!!
            kProperty.isAccessible = true
            when (param.type.javaType.typeName) {
                "int" -> output.writeVarInt(kProperty.get(obj) as Int, true)
                "long" -> output.writeVarLong(kProperty.get(obj) as Long, true)
                "short" -> output.writeShort(kProperty.get(obj) as Int)
                "char" -> output.writeChar(kProperty.get(obj) as Char)
                "byte" -> output.writeByte(kProperty.get(obj) as Byte)
                "double" -> output.writeDouble(kProperty.get(obj) as Double)
                "float" -> output.writeFloat(kProperty.get(obj) as Float)
                "boolean" -> output.writeBoolean(kProperty.get(obj) as Boolean)
                else -> try {
                    kryo.writeClassAndObject(output, kProperty.get(obj))
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to serialize ${param.name} in ${klass.qualifiedName}", e)
                }
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<T>): T {
        assert(type.kotlin == klass)
        val numFields = input.readVarInt(true)
        val fieldTypeHash = input.readInt()

        // A few quick checks for data evolution. Note that this is not guaranteed to catch every problem! But it's
        // good enough for a prototype.
        if (numFields != constructor.parameters.size)
            throw KryoException("Mismatch between number of constructor parameters and number of serialised fields " +
                    "for ${klass.qualifiedName} ($numFields vs ${constructor.parameters.size})")
        if (fieldTypeHash != hashParameters(constructor.parameters))
            throw KryoException("Hashcode mismatch for parameter types for ${klass.qualifiedName}: unsupported type evolution has happened.")

        val args = arrayOfNulls<Any?>(numFields)
        var cursor = 0
        for (param in constructor.parameters) {
            args[cursor++] = when (param.type.javaType.typeName) {
                "int" -> input.readVarInt(true)
                "long" -> input.readVarLong(true)
                "short" -> input.readShort()
                "char" -> input.readChar()
                "byte" -> input.readByte()
                "double" -> input.readDouble()
                "float" -> input.readFloat()
                "boolean" -> input.readBoolean()
                else -> kryo.readClassAndObject(input)
            }
        }
        // If the constructor throws an exception, pass it through instead of wrapping it.
        return try {
            constructor.call(*args)
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }
    }
}

// TODO This is a temporary inefficient serializer for sending InputStreams through RPC. This may be done much more
// efficiently using Artemis's large message feature.
object InputStreamSerializer : Serializer<InputStream>() {
    override fun write(kryo: Kryo, output: Output, stream: InputStream) {
        val buffer = ByteArray(4096)
        while (true) {
            val numberOfBytesRead = stream.read(buffer)
            if (numberOfBytesRead != -1) {
                output.writeInt(numberOfBytesRead, true)
                output.writeBytes(buffer, 0, numberOfBytesRead)
            } else {
                output.writeInt(0)
                break
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<InputStream>): InputStream {
        val chunks = ArrayList<ByteArray>()
        while (true) {
            val chunk = input.readBytesWithLength()
            if (chunk.isEmpty()) {
                break
            } else {
                chunks.add(chunk)
            }
        }
        val flattened = ByteArray(chunks.sumBy { it.size })
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, flattened, offset, chunk.size)
            offset += chunk.size
        }
        return ByteArrayInputStream(flattened)
    }

}

inline fun <T> Kryo.useClassLoader(cl: ClassLoader, body: () -> T): T {
    val tmp = this.classLoader ?: ClassLoader.getSystemClassLoader()
    this.classLoader = cl
    try {
        return body()
    } finally {
        this.classLoader = tmp
    }
}

fun Output.writeBytesWithLength(byteArray: ByteArray) {
    this.writeInt(byteArray.size, true)
    this.writeBytes(byteArray)
}

fun Input.readBytesWithLength(): ByteArray {
    val size = this.readInt(true)
    return this.readBytes(size)
}

/** A serialisation engine that knows how to deserialise code inside a sandbox */
@ThreadSafe
object WireTransactionSerializer : Serializer<WireTransaction>() {
    override fun write(kryo: Kryo, output: Output, obj: WireTransaction) {
        kryo.writeClassAndObject(output, obj.componentGroups)
        kryo.writeClassAndObject(output, obj.privacySalt)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<WireTransaction>): WireTransaction {
        val componentGroups = kryo.readClassAndObject(input) as List<ComponentGroup>
        val privacySalt = kryo.readClassAndObject(input) as PrivacySalt
        return WireTransaction(componentGroups, privacySalt)
    }
}

@ThreadSafe
object NotaryChangeWireTransactionSerializer : Serializer<NotaryChangeWireTransaction>() {
    override fun write(kryo: Kryo, output: Output, obj: NotaryChangeWireTransaction) {
        kryo.writeClassAndObject(output, obj.inputs)
        kryo.writeClassAndObject(output, obj.notary)
        kryo.writeClassAndObject(output, obj.newNotary)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<NotaryChangeWireTransaction>): NotaryChangeWireTransaction {
        val inputs = kryo.readClassAndObject(input) as List<StateRef>
        val notary = kryo.readClassAndObject(input) as Party
        val newNotary = kryo.readClassAndObject(input) as Party

        return NotaryChangeWireTransaction(inputs, notary, newNotary)
    }
}

@ThreadSafe
object SignedTransactionSerializer : Serializer<SignedTransaction>() {
    override fun write(kryo: Kryo, output: Output, obj: SignedTransaction) {
        kryo.writeClassAndObject(output, obj.txBits)
        kryo.writeClassAndObject(output, obj.sigs)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<SignedTransaction>): SignedTransaction {
        return SignedTransaction(
                kryo.readClassAndObject(input) as SerializedBytes<CoreTransaction>,
                kryo.readClassAndObject(input) as List<TransactionSignature>
        )
    }
}

/** For serialising an ed25519 private key */
@ThreadSafe
object Ed25519PrivateKeySerializer : Serializer<EdDSAPrivateKey>() {
    override fun write(kryo: Kryo, output: Output, obj: EdDSAPrivateKey) {
        check(obj.params == Crypto.EDDSA_ED25519_SHA512.algSpec)
        output.writeBytesWithLength(obj.seed)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<EdDSAPrivateKey>): EdDSAPrivateKey {
        val seed = input.readBytesWithLength()
        return EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, Crypto.EDDSA_ED25519_SHA512.algSpec as EdDSANamedCurveSpec))
    }
}

/** For serialising an ed25519 public key */
@ThreadSafe
object Ed25519PublicKeySerializer : Serializer<EdDSAPublicKey>() {
    override fun write(kryo: Kryo, output: Output, obj: EdDSAPublicKey) {
        check(obj.params == Crypto.EDDSA_ED25519_SHA512.algSpec)
        output.writeBytesWithLength(obj.abyte)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<EdDSAPublicKey>): EdDSAPublicKey {
        val A = input.readBytesWithLength()
        return EdDSAPublicKey(EdDSAPublicKeySpec(A, Crypto.EDDSA_ED25519_SHA512.algSpec as EdDSANamedCurveSpec))
    }
}

/** For serialising an ed25519 public key */
@ThreadSafe
object ECPublicKeyImplSerializer : Serializer<ECPublicKeyImpl>() {
    override fun write(kryo: Kryo, output: Output, obj: ECPublicKeyImpl) {
        output.writeBytesWithLength(obj.encoded)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<ECPublicKeyImpl>): ECPublicKeyImpl {
        val A = input.readBytesWithLength()
        val der = DerValue(A)
        return ECPublicKeyImpl.parse(der) as ECPublicKeyImpl
    }
}

// TODO Implement standardized serialization of CompositeKeys. See JIRA issue: CORDA-249.
@ThreadSafe
object CompositeKeySerializer : Serializer<CompositeKey>() {
    override fun write(kryo: Kryo, output: Output, obj: CompositeKey) {
        output.writeInt(obj.threshold)
        output.writeInt(obj.children.size)
        obj.children.forEach { kryo.writeClassAndObject(output, it) }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<CompositeKey>): CompositeKey {
        val threshold = input.readInt()
        val children = readListOfLength<CompositeKey.NodeAndWeight>(kryo, input, minLen = 2)
        val builder = CompositeKey.Builder()
        children.forEach { builder.addKey(it.node, it.weight) }
        return builder.build(threshold) as CompositeKey
    }
}

@ThreadSafe
object PrivateKeySerializer : Serializer<PrivateKey>() {
    override fun write(kryo: Kryo, output: Output, obj: PrivateKey) {
        output.writeBytesWithLength(obj.encoded)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<PrivateKey>): PrivateKey {
        val A = input.readBytesWithLength()
        return Crypto.decodePrivateKey(A)
    }
}

/** For serialising a public key */
@ThreadSafe
object PublicKeySerializer : Serializer<PublicKey>() {
    override fun write(kryo: Kryo, output: Output, obj: PublicKey) {
        // TODO: Instead of encoding to the default X509 format, we could have a custom per key type (space-efficient) serialiser.
        output.writeBytesWithLength(obj.encoded)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<PublicKey>): PublicKey {
        val A = input.readBytesWithLength()
        return Crypto.decodePublicKey(A)
    }
}

/**
 * Helper function for reading lists with number of elements at the beginning.
 * @param minLen minimum number of elements we expect for list to include, defaults to 1
 * @param expectedLen expected length of the list, defaults to null if arbitrary length list read
 */
inline fun <reified T> readListOfLength(kryo: Kryo, input: Input, minLen: Int = 1, expectedLen: Int? = null): List<T> {
    val elemCount = input.readInt()
    if (elemCount < minLen) throw KryoException("Cannot deserialize list, too little elements. Minimum required: $minLen, got: $elemCount")
    if (expectedLen != null && elemCount != expectedLen)
        throw KryoException("Cannot deserialize list, expected length: $expectedLen, got: $elemCount.")
    return (1..elemCount).map { kryo.readClassAndObject(input) as T }
}

/**
 * We need to disable whitelist checking during calls from our Kryo code to register a serializer, since it checks
 * for existing registrations and then will enter our [CordaClassResolver.getRegistration] method.
 */
open class CordaKryo(classResolver: ClassResolver) : Kryo(classResolver, MapReferenceResolver()) {
    override fun register(type: Class<*>?): Registration {
        (classResolver as? CordaClassResolver)?.disableWhitelist()
        try {
            return super.register(type)
        } finally {
            (classResolver as? CordaClassResolver)?.enableWhitelist()
        }
    }

    override fun register(type: Class<*>?, id: Int): Registration {
        (classResolver as? CordaClassResolver)?.disableWhitelist()
        try {
            return super.register(type, id)
        } finally {
            (classResolver as? CordaClassResolver)?.enableWhitelist()
        }
    }

    override fun register(type: Class<*>?, serializer: Serializer<*>?): Registration {
        (classResolver as? CordaClassResolver)?.disableWhitelist()
        try {
            return super.register(type, serializer)
        } finally {
            (classResolver as? CordaClassResolver)?.enableWhitelist()
        }
    }

    override fun register(registration: Registration?): Registration {
        (classResolver as? CordaClassResolver)?.disableWhitelist()
        try {
            return super.register(registration)
        } finally {
            (classResolver as? CordaClassResolver)?.enableWhitelist()
        }
    }
}

inline fun <T : Any> Kryo.register(
        type: KClass<T>,
        crossinline read: (Kryo, Input) -> T,
        crossinline write: (Kryo, Output, T) -> Unit): Registration {
    return register(
            type.java,
            object : Serializer<T>() {
                override fun read(kryo: Kryo, input: Input, clazz: Class<T>): T = read(kryo, input)
                override fun write(kryo: Kryo, output: Output, obj: T) = write(kryo, output, obj)
            }
    )
}

/**
 * Use this method to mark any types which can have the same instance within it more than once. This will make sure
 * the serialised form is stable across multiple serialise-deserialise cycles. Using this on a type with internal cyclic
 * references will throw a stack overflow exception during serialisation.
 */
inline fun <reified T : Any> Kryo.noReferencesWithin() {
    register(T::class.java, NoReferencesSerializer(getSerializer(T::class.java)))
}

class NoReferencesSerializer<T>(val baseSerializer: Serializer<T>) : Serializer<T>() {

    override fun read(kryo: Kryo, input: Input, type: Class<T>): T {
        return kryo.withoutReferences { baseSerializer.read(kryo, input, type) }
    }

    override fun write(kryo: Kryo, output: Output, obj: T) {
        kryo.withoutReferences { baseSerializer.write(kryo, output, obj) }
    }
}

fun <T> Kryo.withoutReferences(block: () -> T): T {
    val previousValue = setReferences(false)
    try {
        return block()
    } finally {
        references = previousValue
    }
}

/** For serialising a Logger. */
@ThreadSafe
object LoggerSerializer : Serializer<Logger>() {
    override fun write(kryo: Kryo, output: Output, obj: Logger) {
        output.writeString(obj.name)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<Logger>): Logger {
        return LoggerFactory.getLogger(input.readString())
    }
}

object ClassSerializer : Serializer<Class<*>>() {
    override fun read(kryo: Kryo, input: Input, type: Class<Class<*>>): Class<*> {
        val className = input.readString()
        return Class.forName(className)
    }

    override fun write(kryo: Kryo, output: Output, clazz: Class<*>) {
        output.writeString(clazz.name)
    }
}

/**
 * For serialising an [X500Name] without touching Sun internal classes.
 */
@ThreadSafe
object X500NameSerializer : Serializer<X500Name>() {
    override fun read(kryo: Kryo, input: Input, type: Class<X500Name>): X500Name {
        return X500Name.getInstance(ASN1InputStream(input.readBytes()).readObject())
    }

    override fun write(kryo: Kryo, output: Output, obj: X500Name) {
        output.writeBytes(obj.encoded)
    }
}

/**
 * For serialising an [CertPath] in an X.500 standard format.
 */
@ThreadSafe
object CertPathSerializer : Serializer<CertPath>() {
    val factory: CertificateFactory = CertificateFactory.getInstance("X.509")
    override fun read(kryo: Kryo, input: Input, type: Class<CertPath>): CertPath {
        return factory.generateCertPath(input)
    }

    override fun write(kryo: Kryo, output: Output, obj: CertPath) {
        output.writeBytes(obj.encoded)
    }
}

/**
 * For serialising an [X509CertificateHolder] in an X.500 standard format.
 */
@ThreadSafe
object X509CertificateSerializer : Serializer<X509CertificateHolder>() {
    override fun read(kryo: Kryo, input: Input, type: Class<X509CertificateHolder>): X509CertificateHolder {
        return X509CertificateHolder(input.readBytes())
    }

    override fun write(kryo: Kryo, output: Output, obj: X509CertificateHolder) {
        output.writeBytes(obj.encoded)
    }
}

fun Kryo.serializationContext(): SerializeAsTokenContext? = context.get(serializationContextKey) as? SerializeAsTokenContext

/**
 * For serializing instances if [Throwable] honoring the fact that [java.lang.Throwable.suppressedExceptions]
 * might be un-initialized/empty.
 * In the absence of this class [CompatibleFieldSerializer] will be used which will assign a *new* instance of
 * unmodifiable collection to [java.lang.Throwable.suppressedExceptions] which will fail some sentinel identity checks
 * e.g. in [java.lang.Throwable.addSuppressed]
 */
@ThreadSafe
class ThrowableSerializer<T>(kryo: Kryo, type: Class<T>) : Serializer<Throwable>(false, true) {

    private companion object {
        private val suppressedField = Throwable::class.java.getDeclaredField("suppressedExceptions")

        private val sentinelValue = let {
            val sentinelField = Throwable::class.java.getDeclaredField("SUPPRESSED_SENTINEL")
            sentinelField.isAccessible = true
            sentinelField.get(null)
        }

        init {
            suppressedField.isAccessible = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val delegate: Serializer<Throwable> = ReflectionSerializerFactory.makeSerializer(kryo, FieldSerializer::class.java, type) as Serializer<Throwable>

    override fun write(kryo: Kryo, output: Output, throwable: Throwable) {
        delegate.write(kryo, output, throwable)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<Throwable>): Throwable {
        val throwableRead = delegate.read(kryo, input, type)
        if(throwableRead.suppressed.isEmpty()) {
            throwableRead.setSuppressedToSentinel()
        }
        return throwableRead
    }

    private fun Throwable.setSuppressedToSentinel() = suppressedField.set(this, sentinelValue)
}