package com.agentstore.dependency.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.util.UUID;

@Entity
@Table(name = "agent_dependencies", uniqueConstraints = @UniqueConstraint(columnNames = {"source_version_id", "target_agent_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDependency extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "source_version_id", nullable = false)
    private UUID sourceVersionId;

    @Column(name = "target_agent_id", nullable = false)
    private UUID targetAgentId;

    @Column(name = "version_constraint", nullable = false)
    private String versionConstraint;

    @Column(nullable = false)
    private boolean required = true;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "max_price_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger maxPriceAtomic;

    @Column(name = "max_calls", nullable = false)
    private int maxCalls = 1;

    public AgentDependency(UUID id, UUID sourceVersionId, UUID targetAgentId, String versionConstraint, boolean required, BigInteger maxPriceAtomic, int maxCalls) {
        this.id = id;
        this.sourceVersionId = sourceVersionId;
        this.targetAgentId = targetAgentId;
        this.versionConstraint = versionConstraint;
        this.required = required;
        this.maxPriceAtomic = maxPriceAtomic;
        this.maxCalls = maxCalls;
    }

    public void update(String versionConstraint, boolean required, BigInteger maxPriceAtomic, int maxCalls) {
        this.versionConstraint = versionConstraint;
        this.required = required;
        this.maxPriceAtomic = maxPriceAtomic;
        this.maxCalls = maxCalls;
    }
}
