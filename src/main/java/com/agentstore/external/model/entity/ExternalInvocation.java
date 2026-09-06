package com.agentstore.external.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "external_invocations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalInvocation extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID executionId;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestHash;

    @Column(nullable = false, unique = true)
    private String receiptTokenHash;

    @Column(nullable = false)
    private Instant receiptExpiresAt;

    public ExternalInvocation(UUID id, UUID executionId, String idempotencyKey,
            String requestHash, String receiptTokenHash, Instant receiptExpiresAt) {
        this.id = id;
        this.executionId = executionId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.receiptTokenHash = receiptTokenHash;
        this.receiptExpiresAt = receiptExpiresAt;
    }
}
