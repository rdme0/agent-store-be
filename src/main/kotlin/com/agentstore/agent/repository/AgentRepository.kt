package com.agentstore.agent.repository

import com.agentstore.agent.model.entity.Agent
import com.agentstore.agent.model.vo.AgentVersionStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.*

interface AgentRepository : JpaRepository<Agent, UUID> {
    fun findBySlug(slug: String): Agent?

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
        @Param("query") query: String?,
        @Param("status") status: AgentVersionStatus,
        @Param("hasCursor") hasCursor: Boolean,
        @Param("cursorCreatedAt") cursorCreatedAt: java.time.Instant?,
        @Param("cursorId") cursorId: UUID?,
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
        @Param("query") query: String?,
        @Param("status") status: AgentVersionStatus,
        @Param("hasCursor") hasCursor: Boolean,
        @Param("cursorNameKey") cursorNameKey: String?,
        @Param("cursorId") cursorId: UUID?,
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
        @Param("agentIds") agentIds: Collection<UUID>,
    ): List<AgentDependencyCountProjection>
}
