package com.agentstore.execution.model.entity;

import com.agentstore.dependency.model.entity.ExecutionQuote;
import com.agentstore.execution.model.vo.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private ExecutionQuote quote;

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL)
    private List<ExecutionStep> steps = new ArrayList<>();

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL)
    private List<ExecutionEvent> events = new ArrayList<>();
}
