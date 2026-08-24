package com.agentstore.agent.model.entity;

import com.agentstore.agent.model.vo.AgentResponseFormat;
import com.agentstore.agent.model.vo.AgentVersionStatus;
import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "agent_versions", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id",
        "semver"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentVersion extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID agentId;

    private UUID capabilityId;

    @Column(nullable = false, length = 32)
    private String semver;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentVersionStatus")
    private AgentVersionStatus status = AgentVersionStatus.DRAFT;

    @Column(nullable = false)
    private String endpoint;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger priceAtomic;

    @Column(nullable = false)
    private String network;

    @Column(nullable = false)
    private String asset;

    @Column(nullable = false)
    private String payTo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentResponseFormat")
    private AgentResponseFormat responseFormat = AgentResponseFormat.JSON;

    private String manifestContent;

    private String manifestSha256;

    public AgentVersion(UUID id, UUID agentId, String semver, String endpoint,
            BigInteger priceAtomic, String network, String asset, String payTo) {
        this(id, agentId, semver, endpoint, priceAtomic, network, asset, payTo,
                AgentResponseFormat.JSON);
    }

    public AgentVersion(UUID id, UUID agentId, String semver, String endpoint,
            BigInteger priceAtomic, String network, String asset, String payTo,
            AgentResponseFormat responseFormat) {
        this(id, agentId, null, semver, endpoint, priceAtomic, network, asset, payTo,
                responseFormat);
    }

    public AgentVersion(UUID id, UUID agentId, UUID capabilityId, String semver, String endpoint,
            BigInteger priceAtomic, String network, String asset, String payTo,
            AgentResponseFormat responseFormat) {
        this.id = id;
        this.agentId = agentId;
        this.capabilityId = capabilityId;
        this.semver = semver;
        this.endpoint = endpoint;
        this.priceAtomic = priceAtomic;
        this.network = network;
        this.asset = asset;
        this.payTo = payTo;
        this.responseFormat = responseFormat == null ? AgentResponseFormat.JSON : responseFormat;
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

    public void replaceManifest(String manifestContent, String manifestSha256) {
        if (status != AgentVersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can replace manifest");
        }
        this.manifestContent = manifestContent;
        this.manifestSha256 = manifestSha256;
    }
}
