package com.agentstore.agent.model.entity;

import com.agentstore.agent.model.vo.AgentVersionStatus;
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
@Table(name = "agent_versions", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "semver"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentVersion extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false, length = 32)
    private String semver;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentVersionStatus")
    private AgentVersionStatus status = AgentVersionStatus.DRAFT;

    @Column(nullable = false)
    private String endpoint;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "price_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger priceAtomic;

    @Column(nullable = false)
    private String network;

    @Column(nullable = false)
    private String asset;

    @Column(name = "pay_to", nullable = false)
    private String payTo;

    public AgentVersion(UUID id, UUID agentId, String semver, String endpoint, BigInteger priceAtomic, String network, String asset, String payTo) {
        this.id = id;
        this.agentId = agentId;
        this.semver = semver;
        this.endpoint = endpoint;
        this.priceAtomic = priceAtomic;
        this.network = network;
        this.asset = asset;
        this.payTo = payTo;
        this.status = AgentVersionStatus.DRAFT;
    }

    public void publish() {
        if (status != AgentVersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be published");
        }
        status = AgentVersionStatus.ACTIVE;
    }

    public void disable() {
        if (status != AgentVersionStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE versions can be disabled");
        }
        status = AgentVersionStatus.DISABLED;
    }
}
