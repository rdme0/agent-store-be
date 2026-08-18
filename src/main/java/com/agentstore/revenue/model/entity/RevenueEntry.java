package com.agentstore.revenue.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.payment.model.vo.PaymentMode;
import com.agentstore.revenue.model.vo.RevenueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.util.UUID;

@Entity
@Table(name = "revenue_entries", uniqueConstraints = {
        @UniqueConstraint(columnNames = "payment_attempt_id"),
        @UniqueConstraint(columnNames = "transaction_hash"),
        @UniqueConstraint(columnNames = "payment_identifier")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevenueEntry extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "developer_id", nullable = false)
    private UUID developerId;

    @Column(name = "execution_step_id", nullable = false)
    private UUID executionStepId;

    @Column(name = "payment_attempt_id", nullable = false, unique = true)
    private UUID paymentAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "RevenueType")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private RevenueType type;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "amount_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger amountAtomic;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, columnDefinition = "PaymentMode")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentMode paymentMode;

    @Column(name = "transaction_hash", unique = true)
    private String transactionHash;

    @Column(name = "payment_identifier", unique = true)
    private String paymentIdentifier;

}
