package com.agentstore.payment.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.payment.model.vo.PaymentAttemptStatus;
import com.agentstore.payment.model.vo.PaymentMode;
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
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID executionStepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "PaymentAttemptStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentAttemptStatus status = PaymentAttemptStatus.REQUIRED;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger amountAtomic;

    @Column(nullable = false)
    private String network;

    @Column(nullable = false)
    private String asset;

    @Column(nullable = false)
    private String payTo;

    private String transactionHash;

    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "PaymentMode")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentMode paymentMode = PaymentMode.SIMULATED;

    private String paymentIdentifier;

    private Instant projectedAt;

    public PaymentAttempt(UUID id, UUID executionStepId, BigInteger amountAtomic, String network,
            String asset, String payTo, PaymentMode paymentMode) {
        this.id = id;
        this.executionStepId = executionStepId;
        this.amountAtomic = amountAtomic;
        this.network = network;
        this.asset = asset;
        this.payTo = payTo;
        this.paymentMode = paymentMode;
        this.status = PaymentAttemptStatus.REQUIRED;
    }

    public void settled(String transactionHash, String paymentIdentifier) {
        this.status = PaymentAttemptStatus.SETTLED;
        this.transactionHash = transactionHash;
        this.paymentIdentifier = paymentIdentifier;
    }

    public void failed(String failureCode) {
        if (status == PaymentAttemptStatus.SETTLED
                || status == PaymentAttemptStatus.RECONCILIATION_REQUIRED) {
            return;
        }
        this.status = PaymentAttemptStatus.FAILED;
        this.failureCode = failureCode;
    }

    public void reconciliationRequired(String failureCode) {
        if (status == PaymentAttemptStatus.SETTLED) {
            return;
        }
        this.status = PaymentAttemptStatus.RECONCILIATION_REQUIRED;
        this.failureCode = failureCode;
    }

    /**
     * Journal/hash remain authoritative; this marker records local work still required after
     * settlement.
     */
    public void markSettlementRecoveryRequired(String failureCode) {
        if (status != PaymentAttemptStatus.SETTLED) {
            throw new IllegalStateException("settlement_recovery_requires_settled_attempt");
        }
        this.failureCode = failureCode;
    }

    public void clearSettlementRecoveryMarker() {
        if (status == PaymentAttemptStatus.SETTLED) {
            this.failureCode = null;
        }
    }

    public void markProjected() {
        if (status != PaymentAttemptStatus.SETTLED) {
            throw new IllegalStateException("projection_requires_settled_attempt");
        }
        this.projectedAt = Instant.now();
        this.failureCode = null;
    }

}
