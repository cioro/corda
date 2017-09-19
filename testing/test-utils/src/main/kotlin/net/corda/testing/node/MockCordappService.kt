package net.corda.testing.node

import net.corda.core.contracts.ContractClassName
import net.corda.core.cordapp.Cordapp
import net.corda.core.cordapp.CordappContext
import net.corda.core.cordapp.CordappService
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.AttachmentId
import java.nio.file.Paths
import java.util.*

class MockCordappService : CordappService {
    val cordappRegistry = mutableListOf<Pair<Cordapp, AttachmentId>>()

    fun addMockCordapp(contractClassName: ContractClassName, services: ServiceHub) {
        val cordapp = Cordapp(listOf(contractClassName), emptyList(), emptyList(), emptyList(), emptyList(), emptySet(), Paths.get(".").toUri().toURL())
        if (cordappRegistry.none { it.first.contractClassNames.contains(contractClassName) }) {
            cordappRegistry.add(Pair(cordapp, findOrImportAttachment(contractClassName.toByteArray(), services)))
        }
    }

    override fun getContractAttachmentID(contractClassName: ContractClassName): AttachmentId? = cordappRegistry.find { it.first.contractClassNames.contains(contractClassName) }?.second
    override fun getAppContext(): CordappContext = TODO()

    private fun findOrImportAttachment(data: ByteArray, services: ServiceHub): AttachmentId {
        return if (services.attachments is MockAttachmentStorage) {
            val existingAttachment = (services.attachments as MockAttachmentStorage).files.filter {
                Arrays.equals(it.value, data)
            }
            if (!existingAttachment.isEmpty()) {
                existingAttachment.keys.first()
            } else {
                services.attachments.importAttachment(data.inputStream())
            }
        } else {
            throw Exception("MockCordappService only requires MockAttachmentStorage")
        }
    }
}