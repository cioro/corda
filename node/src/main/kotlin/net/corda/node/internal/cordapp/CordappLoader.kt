package net.corda.node.internal.cordapp

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult
import net.corda.core.contracts.Contract
import net.corda.core.contracts.UpgradedContract
import net.corda.core.cordapp.Cordapp
import net.corda.core.flows.ContractUpgradeFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.StartableByRPC
import net.corda.core.internal.*
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.services.CordaService
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.utilities.loggerFor
import net.corda.node.internal.classloading.requireAnnotation
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.reflect.KClass
import kotlin.streams.toList

/**
 * Handles CorDapp loading and classpath scanning of CorDapp JARs
 *
 * @property cordappJarPaths The classpath of cordapp JARs
 */
class CordappLoader private constructor(private val cordappJarPaths: List<URL>) {
    val cordapps: List<Cordapp> by lazy { loadCordapps() }

    @VisibleForTesting
    internal val appClassLoader: ClassLoader = javaClass.classLoader

    companion object {
        private val logger = loggerFor<CordappLoader>()

        /**
         * Creates a default CordappLoader intended to be used in non-dev or non-test environments.
         *
         * @param baseDir The directory that this node is running in. Will use this to resolve the plugins directory
         *                  for classpath scanning.
         */
        fun createDefault(baseDir: Path): CordappLoader {
            val pluginsDir = baseDir / "plugins"
            return CordappLoader(if (!pluginsDir.exists()) emptyList<URL>() else pluginsDir.list {
                it.filter { it.isRegularFile() && it.toString().endsWith(".jar") }.map { it.toUri().toURL() }.toList()
            })
        }

        /**
         * Create a dev mode CordappLoader for test environments
         */
        fun createWithTestPackages(testPackages: List<String> = CordappLoader.testPackages) = CordappLoader(testPackages.flatMap(this::createScanPackage))

        /**
         * Creates a dev mode CordappLoader intended only to be used in test environments
         *
         * @param scanJars Uses the JAR URLs provided for classpath scanning and Cordapp detection
         */
        @VisibleForTesting
        fun createDevMode(scanJars: List<URL>) = CordappLoader(scanJars)

        private fun createScanPackage(scanPackage: String): List<URL> {
            val resource = scanPackage.replace('.', '/')
            return this::class.java.classLoader.getResources(resource)
                    .asSequence()
                    .map { path ->
                        if (path.protocol == "jar") {
                            (path.openConnection() as JarURLConnection).jarFileURL.toURI()
                        } else {
                            createDevCordappJar(scanPackage, path, resource)
                        }.toURL()
                    }
                    .toList()
        }

        /** Takes a package of classes and creates a JAR from them - only use in tests */
        private fun createDevCordappJar(scanPackage: String, path: URL, jarPackageName: String): URI {
            if(!generatedCordapps.contains(path)) {
                val cordappDir = File("build/tmp/generated-test-cordapps")
                cordappDir.mkdirs()
                val cordappJAR = File(cordappDir, "$scanPackage-${UUID.randomUUID()}.jar")
                logger.info("Generating a test-only cordapp of classes discovered in $scanPackage at $cordappJAR")
                FileOutputStream(cordappJAR).use {
                    JarOutputStream(it).use { jos ->
                        val scanDir = File(path.toURI())
                        scanDir.walkTopDown().forEach {
                            val entryPath = jarPackageName + "/" + scanDir.toPath().relativize(it.toPath()).toString().replace('\\', '/')
                            jos.putNextEntry(ZipEntry(entryPath))
                            if (it.isFile) {
                                Files.copy(it.toPath(), jos)
                            }
                            jos.closeEntry()
                        }
                    }
                }
                generatedCordapps[path] = cordappJAR.toURI()
            }

            return generatedCordapps[path]!!
        }

        /**
         * A list of test packages that will be scanned as CorDapps and compiled into CorDapp JARs for use in tests only
         */
        @VisibleForTesting
        var testPackages: List<String> = emptyList()
        private val generatedCordapps = mutableMapOf<URL, URI>()
    }

    private fun loadCordapps(): List<Cordapp> {
        return cordappJarPaths.map {
            val scanResult = scanCordapp(it)
            Cordapp(findContractClassNames(scanResult),
                    findInitiatedFlows(scanResult),
                    findRPCFlows(scanResult),
                    findServices(scanResult),
                    findPlugins(it),
                    findCustomSchemas(scanResult),
                    it)
        }
    }

    private fun findServices(scanResult: ScanResult): List<Class<out SerializeAsToken>> {
        return scanResult.getClassesWithAnnotation(SerializeAsToken::class, CordaService::class)
    }

    private fun findInitiatedFlows(scanResult: ScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, InitiatedBy::class)
                // First group by the initiating flow class in case there are multiple mappings
                .groupBy { it.requireAnnotation<InitiatedBy>().value.java }
                .map { (initiatingFlow, initiatedFlows) ->
                    val sorted = initiatedFlows.sortedWith(FlowTypeHierarchyComparator(initiatingFlow))
                    if (sorted.size > 1) {
                        logger.warn("${initiatingFlow.name} has been specified as the inititating flow by multiple flows " +
                                "in the same type hierarchy: ${sorted.joinToString { it.name }}. Choosing the most " +
                                "specific sub-type for registration: ${sorted[0].name}.")
                    }
                    sorted[0]
                }
    }

    private fun findRPCFlows(scanResult: ScanResult): List<Class<out FlowLogic<*>>> {
        fun Class<out FlowLogic<*>>.isUserInvokable(): Boolean {
            return Modifier.isPublic(modifiers) && !isLocalClass && !isAnonymousClass && (!isMemberClass || Modifier.isStatic(modifiers))
        }

        val found = scanResult.getClassesWithAnnotation(FlowLogic::class, StartableByRPC::class).filter { it.isUserInvokable() }
        val coreFlows = listOf(ContractUpgradeFlow.Initiator::class.java)
        return found + coreFlows
    }

    private fun findContractClassNames(scanResult: ScanResult): List<String> {
        return (scanResult.getNamesOfClassesImplementing(Contract::class.java) + scanResult.getNamesOfClassesImplementing(UpgradedContract::class.java)).distinct()
    }

    private fun findPlugins(cordappJarPath: URL): List<CordaPluginRegistry> {
        return ServiceLoader.load(CordaPluginRegistry::class.java, URLClassLoader(arrayOf(cordappJarPath), appClassLoader)).toList().filter {
            cordappJarPath == it.javaClass.protectionDomain.codeSource.location
        }
    }

    private fun findCustomSchemas(scanResult: ScanResult): Set<MappedSchema> {
        return scanResult.getClassesWithSuperclass(MappedSchema::class).toSet()
    }

    private fun scanCordapp(cordappJarPath: URL): ScanResult {
        logger.info("Scanning CorDapp in $cordappJarPath")
        return FastClasspathScanner().addClassLoader(appClassLoader).overrideClasspath(cordappJarPath).scan()
    }

    private class FlowTypeHierarchyComparator(val initiatingFlow: Class<out FlowLogic<*>>) : Comparator<Class<out FlowLogic<*>>> {
        override fun compare(o1: Class<out FlowLogic<*>>, o2: Class<out FlowLogic<*>>): Int {
            return when {
                o1 == o2 -> 0
                o1.isAssignableFrom(o2) -> 1
                o2.isAssignableFrom(o1) -> -1
                else -> throw IllegalArgumentException("${initiatingFlow.name} has been specified as the initiating flow by " +
                        "both ${o1.name} and ${o2.name}")
            }
        }
    }

    private fun <T : Any> loadClass(className: String, type: KClass<T>): Class<out T>? {
        return try {
            appClassLoader.loadClass(className).asSubclass(type.java)
        } catch (e: ClassCastException) {
            logger.warn("As $className must be a sub-type of ${type.java.name}")
            null
        } catch (e: Exception) {
            logger.warn("Unable to load class $className", e)
            null
        }
    }

    private fun <T : Any> ScanResult.getClassesWithSuperclass(type: KClass<T>): List<T> {
        return getNamesOfSubclassesOf(type.java)
                .mapNotNull { loadClass(it, type) }
                .filterNot { Modifier.isAbstract(it.modifiers) }
                .map { it.kotlin.objectOrNewInstance() }
    }

    private fun <T : Any> ScanResult.getClassesWithAnnotation(type: KClass<T>, annotation: KClass<out Annotation>): List<Class<out T>> {
        return getNamesOfClassesWithAnnotation(annotation.java)
                .mapNotNull { loadClass(it, type) }
                .filterNot { Modifier.isAbstract(it.modifiers) }
    }
}
