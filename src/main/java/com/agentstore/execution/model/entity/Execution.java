package com.agentstore.execution.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.execution.model.vo.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID quoteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ExecutionStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger maxBudgetAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger reservedCostAtomic = BigInteger.ZERO;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger actualCostAtomic = BigInteger.ZERO;

    private String failureCode;

    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    public Execution(UUID id, UUID quoteId, BigInteger maxBudgetAtomic, String question,
            JsonNode input) {
        this.id = id;
        this.quoteId = quoteId;
        this.maxBudgetAtomic = maxBudgetAtomic;
        this.question = question;
        this.input = input;
        this.status = ExecutionStatus.PENDING;
    }

    public void start() {
        this.status = ExecutionStatus.RUNNING;
    }

    public void reserve(BigInteger amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("reservation_amount_must_be_non_negative");
        }
        if (status != ExecutionStatus.PENDING && status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("execution_not_active");
        }
        BigInteger available = maxBudgetAtomic.subtract(actualCostAtomic)
                .subtract(reservedCostAtomic);
        if (available.compareTo(amount) < 0) {
            throw new IllegalStateException("budget_exceeded");
        }
        reservedCostAtomic = reservedCostAtomic.add(amount);
    }

    public void settle(BigInteger amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("settlement_amount_must_be_non_negative");
        }
        if (reservedCostAtomic.compareTo(amount) < 0) {
            throw new IllegalStateException("reserved_cost_missing");
        }
        reservedCostAtomic = reservedCostAtomic.subtract(amount);
        actualCostAtomic = actualCostAtomic.add(amount);
    }

    public void release(BigInteger amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("release_amount_must_be_non_negative");
        }
        if (reservedCostAtomic.compareTo(amount) < 0) {
            throw new IllegalStateException("reserved_cost_missing");
        }
        reservedCostAtomic = reservedCostAtomic.subtract(amount);
    }

    public void clearReservation() {
        reservedCostAtomic = BigInteger.ZERO;
    }

    public void fail(String failureCode) {
        this.failureCode = failureCode;
        this.status = ExecutionStatus.FAILED;
    }

    public void complete() {
        this.status = ExecutionStatus.COMPLETED;
    }
}
