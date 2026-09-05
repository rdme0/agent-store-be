package com.agentstore.agent.service

import com.agentstore.agent.config.ProviderReadinessProperties
import com.agentstore.agent.dto.response.AgentVersionReadinessResponse
import com.agentstore.agent.dto.response.AgentVersionResponse
import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.entity.AgentVersionReadiness
import com.agentstore.agent.model.vo.AgentVersionReadinessStatus
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.repository.AgentVersionReadinessRepository
import com.agentstore.agent.repository.AgentVersionRepository
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.execution.validation.AgentOutputFormatException
import com.agentstore.execution.validation.AgentOutputFormatValidator
import com.agentstore.payment.exception.PaymentOutcomeUnknownException
import com.agentstore.x402.dto.internal.X402ProviderVerificationRequestDto
import com.agentstore.x402.exception.ProviderCertificationRejectedException
import com.agentstore.x402.service.X402PaymentService
import com.fasterxml.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class ProviderReadinessService(
    private val versionRepository: AgentVersionRepository,
    private val readinessRepository: AgentVersionReadinessRepository,
    private val functionContractService: FunctionContractService,
    private val x402PaymentService: X402PaymentService,
    private val properties: ProviderReadinessProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(ProviderReadinessService::class.java)
    }

    fun publish(versionId: UUID): AgentVersionResponse {
        return certify(versionId = versionId, requireDraft = true)
    }

    fun verify(versionId: UUID): AgentVersionResponse {
        return certify(versionId = versionId, requireDraft = false)
    }

    private fun certify(versionId: UUID, requireDraft: Boolean): AgentVersionResponse {
        val target = claimCertification(versionId = versionId, requireDraft = requireDraft)
        val certification = try {
            val result = x402PaymentService.certify(request = target.request)
            result
        } catch (exception: PaymentOutcomeUnknownException) {
            markUnknown(versionId = versionId, failureCode = exception.failureCode)
            throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
        } catch (exception: ProviderCertificationRejectedException) {
            if (exception.paymentSettled) {
                markUnknown(versionId = versionId, failureCode = exception.failureCode)
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            markUnavailable(versionId = versionId, failureCode = exception.failureCode)
            throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
        } catch (exception: Exception) {
            markCertificationFailure(
                versionId = versionId,
                failureCode = certificationFailureCode(exception = exception),
                requireDraft = requireDraft,
            )
            throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
        }

        try {
            validateOutput(version = target.version, output = certification.output)
        } catch (_: AgentOutputFormatException) {
            markUnknown(versionId = versionId, failureCode = "provider_output_contract_invalid")
            throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
        } catch (_: DomainClientException) {
            markUnknown(versionId = versionId, failureCode = "provider_output_contract_invalid")
            throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
        } catch (_: Exception) {
            markUnknown(versionId = versionId, failureCode = "provider_certification_validation_unknown")
            throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
        }

        return try {
            completeCertification(
                versionId = versionId,
                transactionHash = certification.transactionHash,
                requireDraft = requireDraft,
            )
        } catch (_: Exception) {
            markUnknownSafely(versionId = versionId, failureCode = "provider_certification_completion_unknown")
            throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
        }
    }

    fun readiness(versionId: UUID): AgentVersionReadinessResponse {
        val readiness = readinessRepository.findById(versionId).orElseThrow {
            DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
        }
        return AgentVersionReadinessResponse.from(readiness = readiness)
    }

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(fixedDelayString = "\${agent-store.provider-readiness.preflight-interval}")
    fun recoverInterruptedCertifications() {
        transaction {
            readinessRepository.findAllByStatus(AgentVersionReadinessStatus.VERIFYING).forEach { readiness ->
                readiness.markUnknown(
                    clock.instant(),
                    "provider_certification_interrupted",
                )
            }
        }
    }

    @Scheduled(fixedDelayString = "\${agent-store.provider-readiness.preflight-interval}")
    fun preflightVerifiedProviders() {
        readinessRepository.findAllByStatus(AgentVersionReadinessStatus.VERIFIED).forEach { readiness ->
            preflight(versionId = readiness.versionId)
        }
    }

    private fun preflight(versionId: UUID) {
        val target = transactionOrNull {
            val version = versionRepository.findById(versionId).orElse(null)
                ?: return@transactionOrNull null
            if (version.status != AgentVersionStatus.ACTIVE || version.verificationInput == null) {
                return@transactionOrNull null
            }
            verificationTarget(version = version)
        } ?: return

        try {
            x402PaymentService.preflightProvider(request = target.request)
            transaction {
                val readiness = readinessRepository.findLockedByVersionId(versionId)
                if (readiness?.status == AgentVersionReadinessStatus.VERIFIED) {
                    readiness.recordPreflight(clock.instant())
                }
            }
        } catch (_: Exception) {
            transaction {
                val readiness = readinessRepository.findLockedByVersionId(versionId)
                if (readiness?.status == AgentVersionReadinessStatus.VERIFIED) {
                    readiness.markUnavailable(
                        clock.instant(),
                        "x402_preflight_failed",
                    )
                }
            }
        }
    }

    private fun claimCertification(versionId: UUID, requireDraft: Boolean): ProviderVerificationTargetDto {
        return transaction {
            val version = versionRepository.findById(versionId).orElseThrow {
                DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
            }
            val readiness = readinessRepository.findLockedByVersionId(versionId)
                ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
            if (readiness.status == AgentVersionReadinessStatus.VERIFYING) {
                throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_IN_PROGRESS)
            }
            if (readiness.status == AgentVersionReadinessStatus.UNKNOWN) {
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            if (requireDraft && version.status != AgentVersionStatus.DRAFT) {
                throw DomainClientException(ErrorCode.INVALID_VERSION_TRANSITION)
            }
            if (!requireDraft && version.status != AgentVersionStatus.ACTIVE) {
                throw DomainClientException(ErrorCode.INVALID_VERSION_TRANSITION)
            }
            if (!requireDraft && readiness.status !in setOf(
                    AgentVersionReadinessStatus.UNVERIFIED,
                    AgentVersionReadinessStatus.UNAVAILABLE,
                )
            ) {
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            if (version.priceAtomic > properties.maxCertificationPriceAtomic) {
                throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
            }
            readiness.beginVerification()
            verificationTarget(version = version)
        }
    }

    private fun verificationTarget(version: AgentVersion): ProviderVerificationTargetDto {
        val functionContractId = version.functionContractId
            ?: throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
        val verificationInput = version.verificationInput
            ?: throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
        val contract = functionContractService.requireFunctionContract(id = functionContractId)
        functionContractService.validateInstance(
            schema = contract.inputSchema,
            value = verificationInput,
            errorCode = ErrorCode.AGENT_INPUT_SCHEMA_INVALID,
        )
        return ProviderVerificationTargetDto(
            version = version,
            request = X402ProviderVerificationRequestDto(
                endpoint = version.endpoint,
                amountAtomic = version.priceAtomic.toString(),
                network = version.network,
                asset = version.asset,
                payTo = version.payTo,
                input = verificationInput,
            ),
        )
    }

    private fun validateOutput(version: AgentVersion, output: JsonNode) {
        val functionContractId = version.functionContractId
            ?: throw DomainClientException(ErrorCode.PROVIDER_VERIFICATION_REQUIRED)
        AgentOutputFormatValidator.validate(format = version.responseFormat, output = output)
        val contract = functionContractService.requireFunctionContract(id = functionContractId)
        functionContractService.validateInstance(
            schema = contract.outputSchema,
            value = output,
            errorCode = ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
        )
    }

    private fun completeCertification(
        versionId: UUID,
        transactionHash: String,
        requireDraft: Boolean,
    ): AgentVersionResponse {
        return transaction {
            val version = versionRepository.findById(versionId).orElseThrow {
                DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
            }
            val readiness = readinessRepository.findLockedByVersionId(versionId)
                ?: throw DomainClientException(ErrorCode.AGENT_VERSION_NOT_FOUND)
            if (readiness.status != AgentVersionReadinessStatus.VERIFYING) {
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            if (requireDraft && version.status != AgentVersionStatus.DRAFT) {
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            if (!requireDraft && version.status != AgentVersionStatus.ACTIVE) {
                throw DomainClientException(ErrorCode.PROVIDER_NOT_READY)
            }
            if (requireDraft) {
                version.publish()
            }
            readiness.verify(clock.instant(), transactionHash)
            AgentVersionResponse.from(version = version)
        }
    }

    private fun markUnknown(versionId: UUID, failureCode: String) {
        transaction {
            readinessRepository.findLockedByVersionId(versionId)?.markUnknown(clock.instant(), failureCode)
        }
    }

    /**
     * A paid certification must never be retried merely because persisting its final state failed.
     * If this immediate transition also cannot reach PostgreSQL, the scheduled recovery above will
     * turn the retained VERIFYING row into UNKNOWN once the database is available again.
     */
    private fun markUnknownSafely(versionId: UUID, failureCode: String) {
        runCatching { markUnknown(versionId = versionId, failureCode = failureCode) }
            .onFailure { exception ->
                logger.error(
                    "Could not persist UNKNOWN readiness after paid provider certification; recovery will retry",
                    exception,
                )
            }
    }

    private fun markCertificationFailure(versionId: UUID, failureCode: String, requireDraft: Boolean) {
        transaction {
            val readiness = readinessRepository.findLockedByVersionId(versionId) ?: return@transaction
            if (requireDraft) {
                readiness.markUnverified(failureCode)
                return@transaction
            }
            readiness.markUnavailable(clock.instant(), failureCode)
        }
    }

    private fun markUnavailable(versionId: UUID, failureCode: String) {
        transaction {
            readinessRepository.findLockedByVersionId(versionId)?.markUnavailable(clock.instant(), failureCode)
        }
    }

    private fun certificationFailureCode(exception: Exception): String {
        return when (exception) {
            is DomainClientException -> "provider_contract_invalid"
            else -> "provider_certification_failed"
        }
    }

    private fun <T> transaction(action: () -> T): T {
        return requireNotNull(transactionTemplate.execute { action() })
    }

    private fun <T> transactionOrNull(action: () -> T?): T? {
        return transactionTemplate.execute { action() }
    }
}

private data class ProviderVerificationTargetDto(
    val version: AgentVersion,
    val request: X402ProviderVerificationRequestDto,
)
