package com.agentstore.execution.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "execution_events", uniqueConstraints = @UniqueConstraint(columnNames = {"execution_id", "sequence"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionEvent extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    public ExecutionEvent(UUID id, UUID executionId, int sequence, String type, JsonNode payload) {
        this.id = id;
        this.executionId = executionId;
        this.sequence = sequence;
        this.type = type;
        this.payload = payload;
    }
}
