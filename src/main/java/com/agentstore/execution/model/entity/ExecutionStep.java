package com.agentstore.execution.model.entity;

import com.agentstore.agent.model.entity.AgentVersion;
import com.agentstore.execution.model.vo.ExecutionStepStatus;
import com.agentstore.payment.model.entity.PaymentAttempt;
import com.agentstore.revenue.model.entity.RevenueEntry;
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
@Table(name = "execution_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionStep {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_step_id")
    private ExecutionStep parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<ExecutionStep> children = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_version_id", nullable = false)
    private AgentVersion agentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ExecutionStepStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ExecutionStepStatus status = ExecutionStepStatus.CREATED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "call_path", nullable = false, columnDefinition = "jsonb")
    private JsonNode callPath;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_fingerprint")
    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "cost_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger costAtomic = BigInteger.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

    @Column(name = "failure_code")
    private String failureCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "executionStep", cascade = CascadeType.ALL)
    private List<PaymentAttempt> paymentAttempts = new ArrayList<>();

    @OneToMany(mappedBy = "executionStep")
    private List<RevenueEntry> revenueEntries = new ArrayList<>();
}
