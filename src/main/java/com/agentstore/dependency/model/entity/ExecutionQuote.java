package com.agentstore.dependency.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_quotes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionQuote extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "root_version_id", nullable = false)
    private UUID rootVersionId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "max_cost_atomic", nullable = false, columnDefinition = "BIGINT")
    private BigInteger maxCostAtomic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode snapshot;

    public ExecutionQuote(UUID id, UUID rootVersionId, Instant expiresAt, BigInteger maxCostAtomic, JsonNode snapshot) {
        this.id = id;
        this.rootVersionId = rootVersionId;
        this.expiresAt = expiresAt;
        this.maxCostAtomic = maxCostAtomic;
        this.snapshot = snapshot;
    }
}
