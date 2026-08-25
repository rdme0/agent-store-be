package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.vo.AgentVersionStatus
import com.agentstore.agent.model.vo.AgentUsageType
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AgentRepository : JpaRepository<Agent, UUID> {
    fun findByCode(code: String): Agent?

    @Query(
        """
        select agent
        from Agent agent
        where exists (
            select 1
            from AgentVersion version
            where version.agentId = agent.id
              and version.status = :status
        )
          and (cast(:usageType as string) is null or agent.usageType = :usageType)
          and (
            coalesce(:query, '') = ''
            or lower(agent.name) like lower(concat('%', coalesce(:query, ''), '%'))
            or lower(agent.description) like lower(concat('%', coalesce(:query, ''), '%'))
          )
          and (
            :hasCursor = false
            or agent.createdAt < coalesce(:cursorCreatedAt, agent.createdAt)
            or (
                agent.createdAt = coalesce(:cursorCreatedAt, agent.createdAt)
                and agent.id < coalesce(:cursorId, agent.id)
            )
          )
        order by agent.createdAt desc, agent.id desc
        """
    )
    fun findMarketplaceAgentsByCreatedAtDesc(
        query: String?,
        status: AgentVersionStatus,
        usageType: AgentUsageType?,
        hasCursor: Boolean,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        pageable: Pageable,
    ): List<Agent>

    @Query(
        """
        select agent
        from Agent agent
        where exists (
            select 1
            from AgentVersion version
            where version.agentId = agent.id
              and version.status = :status
        )
          and (cast(:usageType as string) is null or agent.usageType = :usageType)
          and (
            coalesce(:query, '') = ''
            or lower(agent.name) like lower(concat('%', coalesce(:query, ''), '%'))
            or lower(agent.description) like lower(concat('%', coalesce(:query, ''), '%'))
          )
          and (
            :hasCursor = false
            or lower(agent.name) > coalesce(:cursorNameKey, lower(agent.name))
            or (
                lower(agent.name) = coalesce(:cursorNameKey, lower(agent.name))
                and agent.id > coalesce(:cursorId, agent.id)
            )
          )
        order by lower(agent.name) asc, agent.id asc
        """
    )
    fun findMarketplaceAgentsByNameAsc(
        query: String?,
        status: AgentVersionStatus,
        usageType: AgentUsageType?,
        hasCursor: Boolean,
        cursorNameKey: String?,
        cursorId: UUID?,
        pageable: Pageable,
    ): List<Agent>

    @Query(
        """
        select version.agentId as agentId, count(distinct dependency.targetAgentId) as dependencyCount
        from AgentVersion version
        left join AgentDependency dependency on dependency.sourceVersionId = version.id
        where version.agentId in :agentIds
        group by version.agentId
        """
    )
    fun countDistinctDependenciesByAgentIds(
        agentIds: Collection<UUID>,
    ): List<AgentDependencyCountProjection>
}
