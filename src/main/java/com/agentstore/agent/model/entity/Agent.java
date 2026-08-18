package com.agentstore.agent.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Entity
@Table(name = "agents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "developer_id", nullable = false)
    private UUID developerId;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    public Agent(UUID id, UUID developerId, String slug, String name, String description) {
        this.id = id;
        this.developerId = developerId;
        this.slug = slug;
        this.name = name;
        this.description = description;
    }

    public void updateMetadata(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
