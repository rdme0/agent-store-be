package com.agentstore.agent.model.entity;

import com.agentstore.agent.model.vo.AgentVersionReadinessStatus;
import com.agentstore.common.model.entity.BaseEntity;
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
@Table(name = "agent_version_readiness")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentVersionReadiness extends BaseEntity {

    @Id
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentVersionReadinessStatus")
    private AgentVersionReadinessStatus status;

    private Instant lastPaidCertificationAt;

    private Instant lastPreflightAt;

    private String certificationTransactionHash;

    private String failureCode;

    public AgentVersionReadiness(UUID versionId) {
        this.versionId = versionId;
        this.status = AgentVersionReadinessStatus.UNVERIFIED;
    }

    public void beginVerification() {
        this.status = AgentVersionReadinessStatus.VERIFYING;
        this.failureCode = null;
    }

    public void verify(Instant certifiedAt, String transactionHash) {
        this.status = AgentVersionReadinessStatus.VERIFIED;
        this.lastPaidCertificationAt = certifiedAt;
        this.certificationTransactionHash = transactionHash;
        this.failureCode = null;
    }

    public void markUnverified(String failureCode) {
        this.status = AgentVersionReadinessStatus.UNVERIFIED;
        this.failureCode = failureCode;
    }

    public void markUnavailable(Instant checkedAt, String failureCode) {
        this.status = AgentVersionReadinessStatus.UNAVAILABLE;
        this.lastPreflightAt = checkedAt;
        this.failureCode = failureCode;
    }

    public void markUnknown(Instant checkedAt, String failureCode) {
        this.status = AgentVersionReadinessStatus.UNKNOWN;
        this.lastPaidCertificationAt = checkedAt;
        this.failureCode = failureCode;
    }

    public void recordPreflight(Instant checkedAt) {
        this.lastPreflightAt = checkedAt;
        this.failureCode = null;
    }
}
