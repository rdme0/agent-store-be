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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "execution_step_id", nullable = false)
    private UUID executionStepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "PaymentAttemptStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentAttemptStatus status = PaymentAttemptStatus.REQUIRED;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "amount_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger amountAtomic;

    @Column(nullable = false)
    private String network;

    @Column(nullable = false)
    private String asset;

    @Column(name = "pay_to", nullable = false)
    private String payTo;

    @Column(name = "transaction_hash")
    private String transactionHash;

    @Column(name = "failure_code")
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, columnDefinition = "PaymentMode")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentMode paymentMode = PaymentMode.SIMULATED;

    @Column(name = "payment_identifier")
    private String paymentIdentifier;

    public PaymentAttempt(UUID id, UUID executionStepId, BigInteger amountAtomic, String network, String asset, String payTo, PaymentMode paymentMode) {
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
        if (status == PaymentAttemptStatus.SETTLED || status == PaymentAttemptStatus.RECONCILIATION_REQUIRED) return;
        this.status = PaymentAttemptStatus.FAILED;
        this.failureCode = failureCode;
    }

    public void reconciliationRequired(String failureCode) {
        if (status == PaymentAttemptStatus.SETTLED) return;
        this.status = PaymentAttemptStatus.RECONCILIATION_REQUIRED;
        this.failureCode = failureCode;
    }

}
