package com.agentstore.external.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.external.model.vo.ExternalInvocationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "external_invocation_intents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalInvocationIntent extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID quoteId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private String receiptTokenHash;

    @Column(nullable = false)
    private Instant receiptExpiresAt;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger providerCostAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger platformFeeAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger totalCostAtomic;

    @Column(nullable = false)
    private String payTo;

    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ExternalInvocationStatus")
    private ExternalInvocationStatus status;

    @Column(nullable = false)
    private Instant expiresAt;

    private String paymentFingerprint;

    private String payer;

    private String transactionHash;

    private UUID executionId;

    private String failureCode;

    public ExternalInvocationIntent(UUID id, UUID quoteId, String idempotencyKey, String requestHash,
            String receiptTokenHash, Instant receiptExpiresAt, BigInteger providerCostAtomic,
            BigInteger platformFeeAtomic, BigInteger totalCostAtomic, String payTo, String question,
            JsonNode input, Instant expiresAt) {
        this.id = id;
        this.quoteId = quoteId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.receiptTokenHash = receiptTokenHash;
        this.receiptExpiresAt = receiptExpiresAt;
        this.providerCostAtomic = providerCostAtomic;
        this.platformFeeAtomic = platformFeeAtomic;
        this.totalCostAtomic = totalCostAtomic;
        this.payTo = payTo;
        this.question = question;
        this.input = input;
        this.expiresAt = expiresAt;
        this.status = ExternalInvocationStatus.PAYMENT_PENDING;
    }

    public void markSettling(String paymentFingerprint) {
        if (status != ExternalInvocationStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("external_intent_not_payment_pending");
        }
        this.paymentFingerprint = paymentFingerprint;
        this.status = ExternalInvocationStatus.SETTLING;
    }

    public void markExecutionCreated(UUID executionId) {
        if (status != ExternalInvocationStatus.SETTLED) {
            throw new IllegalStateException("external_intent_not_settled");
        }
        this.executionId = executionId;
        this.status = ExternalInvocationStatus.EXECUTION_CREATED;
    }

    public void markSettled(String payer, String transactionHash) {
        if (status != ExternalInvocationStatus.SETTLING) {
            throw new IllegalStateException("external_intent_not_settling");
        }
        this.payer = payer;
        this.transactionHash = transactionHash;
        this.status = ExternalInvocationStatus.SETTLED;
    }

    public void markReconciliationRequired(String failureCode) {
        if (status == ExternalInvocationStatus.EXECUTION_CREATED) {
            return;
        }
        this.failureCode = failureCode;
        this.status = ExternalInvocationStatus.RECONCILIATION_REQUIRED;
    }

    public void fail(String failureCode) {
        if (status != ExternalInvocationStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("external_intent_not_payment_pending");
        }
        this.failureCode = failureCode;
        this.status = ExternalInvocationStatus.FAILED;
    }
}
