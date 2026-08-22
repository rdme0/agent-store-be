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
import java.math.BigInteger;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(nullable = false)
    private UUID developerId;

    @Column(nullable = false)
    private UUID executionStepId;

    @Column(nullable = false, unique = true)
    private UUID paymentAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "RevenueType")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private RevenueType type;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger amountAtomic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "PaymentMode")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PaymentMode paymentMode;

    @Column(unique = true)
    private String transactionHash;

    @Column(unique = true)
    private String paymentIdentifier;

    public RevenueEntry(UUID id, UUID developerId, UUID executionStepId, UUID paymentAttemptId,
            RevenueType type, BigInteger amountAtomic, PaymentMode paymentMode,
            String transactionHash, String paymentIdentifier) {
        this.id = id;
        this.developerId = developerId;
        this.executionStepId = executionStepId;
        this.paymentAttemptId = paymentAttemptId;
        this.type = type;
        this.amountAtomic = amountAtomic;
        this.paymentMode = paymentMode;
        this.transactionHash = transactionHash;
        this.paymentIdentifier = paymentIdentifier;
    }

}
