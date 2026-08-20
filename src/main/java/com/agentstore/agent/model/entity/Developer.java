package com.agentstore.agent.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "developers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Developer extends BaseEntity {
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    public Developer(UUID id, User user, String displayName) {
        this.id = id;
        this.user = user;
        this.displayName = displayName;
    }
}
