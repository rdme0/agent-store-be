package com.agentstore.dependency

import com.agentstore.agent.service.FunctionContractService
import com.agentstore.agent.service.AgentService
import com.agentstore.common.exception.client.DomainClientException
import com.agentstore.common.exception.constants.ErrorCode
import com.agentstore.dependency.repository.AgentDependencyAllowedProviderRepository
import com.agentstore.dependency.repository.AgentDependencyRepository
import com.agentstore.dependency.resolver.DependencyResolver
import com.agentstore.execution.service.ProviderMetricService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class DependencyResolverVersionConstraintTest {
    private val resolver = DependencyResolver(
        agentService = mock(AgentService::class.java),
        functionContractService = mock(FunctionContractService::class.java),
        dependencyRepository = mock(AgentDependencyRepository::class.java),
        allowedProviderRepository = mock(AgentDependencyAllowedProviderRepository::class.java),
        providerMetricService = mock(ProviderMetricService::class.java),
    )

    @Test
    fun `normalizes Python style comparator ranges`() {
        assertEquals(
            ">=1.0.0,<2.0.0",
            resolver.normalizeConstraint(constraint = " >= 1.0.0, < 2.0.0 "),
        )
        assertEquals("==1.2.3", resolver.normalizeConstraint(constraint = "==1.2.3"))
    }

    @Test
    fun `matches exact and bounded comparator ranges with AND semantics`() {
        assertTrue(resolver.matches(version = "1.2.3", constraint = "==1.2.3"))
        assertFalse(resolver.matches(version = "1.2.4", constraint = "==1.2.3"))
        assertTrue(resolver.matches(version = "1.9.9", constraint = ">=1.0.0,<2.0.0"))
        assertFalse(resolver.matches(version = "2.0.0", constraint = ">=1.0.0,<2.0.0"))
        assertFalse(resolver.matches(version = "0.9.9", constraint = ">=1.0.0,<2.0.0"))
        assertTrue(resolver.matches(version = "99.99.99", constraint = "*"))
    }

    @Test
    fun `rejects legacy and unsupported version constraints`() {
        listOf("^1.0.0", "~1.0.0", "1.0.0", "~=1.0.0", ">=1.0.0 <2.0.0").forEach { constraint ->
            val exception = assertThrows(DomainClientException::class.java) {
                resolver.validateConstraint(constraint = constraint)
            }

            assertEquals(ErrorCode.INVALID_VERSION_CONSTRAINT, exception.errorCode)
        }
    }
}
