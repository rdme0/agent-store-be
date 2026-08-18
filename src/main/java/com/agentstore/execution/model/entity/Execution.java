package com.agentstore.execution.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.execution.model.vo.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.CascadeType;
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
@Table(name = "executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ExecutionStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "max_budget_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger maxBudgetAtomic;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "reserved_cost_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger reservedCostAtomic = BigInteger.ZERO;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "actual_cost_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger actualCostAtomic = BigInteger.ZERO;

    @Column(name = "failure_code")
    private String failureCode;

    @Column
    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    public Execution(UUID id, UUID quoteId, BigInteger maxBudgetAtomic, String question, JsonNode input) {
        this.id = id;
        this.quoteId = quoteId;
        this.maxBudgetAtomic = maxBudgetAtomic;
        this.question = question;
        this.input = input;
        this.status = ExecutionStatus.PENDING;
    }

    public void start() { this.status = ExecutionStatus.RUNNING; }

    public void fail(String failureCode) {
        this.failureCode = failureCode;
        this.status = ExecutionStatus.FAILED;
    }
}
