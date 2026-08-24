package com.agentstore.agent.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import com.agentstore.agent.model.vo.AgentUsageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID developerId;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentUsageType")
    private AgentUsageType usageType;

    public Agent(UUID id, UUID developerId, String slug, String name, String description, AgentUsageType usageType) {
        this.id = id;
        this.developerId = developerId;
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.usageType = usageType;
    }

    public Agent(UUID id, UUID developerId, String slug, String name, String description) {
        this(id, developerId, slug, name, description, AgentUsageType.INTERNAL_COMPONENT);
    }

    public void updateMetadata(String name, String description, AgentUsageType usageType) {
        this.name = name;
        this.description = description;
        this.usageType = usageType;
    }
}
