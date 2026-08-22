package com.agentstore.execution.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.execution.model.vo.ExecutionStepStatus;
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
@Table(name = "execution_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionStep extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID executionId;

    private UUID parentStepId;

    @Column(nullable = false)
    private UUID agentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ExecutionStepStatus")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ExecutionStepStatus status = ExecutionStepStatus.CREATED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode callPath;

    private String idempotencyKey;

    private String requestFingerprint;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(nullable = false, columnDefinition = "BIGINT")
    private BigInteger costAtomic = BigInteger.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

    private String failureCode;

    public ExecutionStep(UUID id, UUID executionId, UUID parentStepId, UUID agentVersionId,
            JsonNode callPath) {
        this.id = id;
        this.executionId = executionId;
        this.parentStepId = parentStepId;
        this.agentVersionId = agentVersionId;
        this.callPath = callPath;
        this.status = ExecutionStepStatus.CREATED;
    }

    public ExecutionStep(UUID id, UUID executionId, UUID parentStepId, UUID agentVersionId,
            JsonNode callPath, String idempotencyKey) {
        this(id, executionId, parentStepId, agentVersionId, callPath);
        this.idempotencyKey = idempotencyKey;
    }

    public void complete(JsonNode output, BigInteger costAtomic) {
        this.output = output;
        this.costAtomic = costAtomic;
        this.status = ExecutionStepStatus.COMPLETED;
    }

    public void paymentRequired() {
        this.status = ExecutionStepStatus.PAYMENT_REQUIRED;
    }

    public void paymentSettled() {
        this.status = ExecutionStepStatus.PAYMENT_SETTLED;
    }

    public void running() {
        this.status = ExecutionStepStatus.RUNNING;
    }

    public void fail(String failureCode) {
        this.failureCode = failureCode;
        this.status = ExecutionStepStatus.FAILED;
    }
}
