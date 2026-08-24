package com.agentstore.execution.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.execution.model.vo.AgentInvocationOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_invocation_observations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentInvocationObservation extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID executionStepId;

    @Column(nullable = false)
    private UUID agentVersionId;

    private UUID functionContractId;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant completedAt;

    private Long latencyMillis;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "AgentInvocationOutcome")
    private AgentInvocationOutcome outcome;

    public AgentInvocationObservation(UUID id, UUID executionStepId, UUID agentVersionId,
            UUID functionContractId, Instant startedAt) {
        this.id = id;
        this.executionStepId = executionStepId;
        this.agentVersionId = agentVersionId;
        this.functionContractId = functionContractId;
        this.startedAt = startedAt;
    }

    public void finish(AgentInvocationOutcome outcome, Instant completedAt) {
        if (this.outcome != null) {
            return;
        }
        this.outcome = outcome;
        this.completedAt = completedAt;
        this.latencyMillis = Math.max(0L, completedAt.toEpochMilli() - startedAt.toEpochMilli());
    }
}
