package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.AgentVersion
import com.agentstore.agent.model.vo.AgentVersionReadinessStatus
import com.agentstore.agent.model.vo.AgentVersionStatus
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AgentVersionRepository : JpaRepository<AgentVersion, UUID> {
    fun findWithAgentById(id: UUID): AgentVersion? {
        return findById(id).orElse(null)
    }

    fun findByAgentIdAndSemver(agentId: UUID, semver: String): AgentVersion?
    fun findAllByAgentId(agentId: UUID): List<AgentVersion>
    fun findAllByAgentIdAndStatus(agentId: UUID, status: AgentVersionStatus): List<AgentVersion>
    fun findAllByFunctionContractIdAndStatus(functionContractId: UUID, status: AgentVersionStatus): List<AgentVersion>

    fun findByIdAndStatus(id: UUID, status: AgentVersionStatus): AgentVersion?

    @Query(
        """
        select version
        from AgentVersion version
        where version.agentId = :agentId
          and version.status = :versionStatus
          and exists (
              select readiness
              from AgentVersionReadiness readiness
              where readiness.versionId = version.id
                and readiness.status = :readinessStatus
          )
        """,
    )
    fun findAllReadyByAgentId(
        agentId: UUID,
        versionStatus: AgentVersionStatus,
        readinessStatus: AgentVersionReadinessStatus,
    ): List<AgentVersion>

    @Query(
        """
        select version
        from AgentVersion version
        where version.functionContractId = :functionContractId
          and version.status = :versionStatus
          and exists (
              select readiness
              from AgentVersionReadiness readiness
              where readiness.versionId = version.id
                and readiness.status = :readinessStatus
          )
        """,
    )
    fun findAllReadyByFunctionContractId(
        functionContractId: UUID,
        versionStatus: AgentVersionStatus,
        readinessStatus: AgentVersionReadinessStatus,
    ): List<AgentVersion>

}
