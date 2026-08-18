package com.agentstore.revenue.model.entity;

import com.agentstore.agent.model.entity.Developer;
import com.agentstore.execution.model.entity.ExecutionStep;
import com.agentstore.payment.model.entity.PaymentAttempt;
import com.agentstore.payment.model.vo.PaymentMode;
import com.agentstore.revenue.model.vo.RevenueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "revenue_entries", uniqueConstraints = {
        @UniqueConstraint(columnNames = "payment_attempt_id"),
        @UniqueConstraint(columnNames = "transaction_hash"),
        @UniqueConstraint(columnNames = "payment_identifier")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevenueEntry {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "developer_id", nullable = false)
    private Developer developer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_step_id", nullable = false)
    private ExecutionStep executionStep;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_attempt_id", nullable = false, unique = true)
    private PaymentAttempt paymentAttempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "RevenueType")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private RevenueType type;

    @Column(name = "amount_atomic", nullable = false, precision = 39, scale = 0)
    private BigInteger amountAtomic;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, columnDefinition = "PaymentMode")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentMode paymentMode;

    @Column(name = "transaction_hash", unique = true)
    private String transactionHash;

    @Column(name = "payment_identifier", unique = true)
    private String paymentIdentifier;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
