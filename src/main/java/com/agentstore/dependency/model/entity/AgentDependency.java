package com.agentstore.dependency.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigInteger;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_dependencies", uniqueConstraints = @UniqueConstraint(columnNames = {
        "source_version_id", "target_agent_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDependency extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID sourceVersionId;

    @Column(nullable = false)
    private UUID targetAgentId;

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
}
