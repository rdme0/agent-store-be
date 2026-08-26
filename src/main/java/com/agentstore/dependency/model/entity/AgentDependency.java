package com.agentstore.dependency.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.dependency.model.vo.ProviderScope;
import com.agentstore.dependency.model.vo.ProviderSelectionStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_dependencies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDependency extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID sourceVersionId;

    private UUID targetAgentId;

    private UUID functionContractId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "ProviderScope")
    private ProviderScope providerScope;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "ProviderSelectionStrategy")
    private ProviderSelectionStrategy selectionStrategy;

    private Integer minReliabilityPercent;

    private Integer maxP95LatencyMillis;

    @Column(nullable = false)
    private String versionConstraint;

    @Column(nullable = false)
    private boolean required = true;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger maxPriceAtomic;

    @Column(nullable = false)
    private int maxCalls = 1;

    public AgentDependency(UUID id, UUID sourceVersionId, UUID targetAgentId,
            String versionConstraint, boolean required, BigInteger maxPriceAtomic, int maxCalls) {
        this.id = id;
        this.sourceVersionId = sourceVersionId;
        this.targetAgentId = targetAgentId;
        this.versionConstraint = versionConstraint;
        this.required = required;
        this.maxPriceAtomic = maxPriceAtomic;
        this.maxCalls = maxCalls;
    }

    public void update(String versionConstraint, boolean required, BigInteger maxPriceAtomic,
            int maxCalls) {
        this.versionConstraint = versionConstraint;
        this.required = required;
        this.maxPriceAtomic = maxPriceAtomic;
        this.maxCalls = maxCalls;
    }

    public void configureFunctionSelection(UUID functionContractId, ProviderScope providerScope,
            ProviderSelectionStrategy selectionStrategy, Integer minReliabilityPercent,
            Integer maxP95LatencyMillis) {
        this.functionContractId = functionContractId;
        this.providerScope = providerScope;
        this.selectionStrategy = selectionStrategy;
        this.minReliabilityPercent = minReliabilityPercent;
        this.maxP95LatencyMillis = maxP95LatencyMillis;
    }
}
