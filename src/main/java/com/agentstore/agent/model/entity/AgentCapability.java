package com.agentstore.agent.model.entity;

import com.agentstore.agent.model.vo.AgentResponseFormat;
import com.agentstore.common.model.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_capabilities", uniqueConstraints = @UniqueConstraint(columnNames = {
        "key", "contract_version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentCapability extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String key;

    @Column(nullable = false, length = 32)
    private String contractVersion;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentResponseFormat")
    private AgentResponseFormat responseFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode inputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode outputSchema;

    public AgentCapability(UUID id, String key, String contractVersion, String name,
            String description, AgentResponseFormat responseFormat, JsonNode inputSchema,
            JsonNode outputSchema) {
        this.id = id;
        this.key = key;
        this.contractVersion = contractVersion;
        this.name = name;
        this.description = description;
        this.responseFormat = responseFormat;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
    }
}
