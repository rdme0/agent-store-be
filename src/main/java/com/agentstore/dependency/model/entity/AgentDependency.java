package com.agentstore.dependency.model.entity;

import com.agentstore.agent.model.entity.Agent;
import com.agentstore.agent.model.entity.AgentVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_dependencies", uniqueConstraints = @UniqueConstraint(columnNames = {"source_version_id", "target_agent_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDependency {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_version_id", nullable = false)
    private AgentVersion sourceVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_agent_id", nullable = false)
    private Agent targetAgent;

    @Column(name = "version_constraint", nullable = false)
    private String versionConstraint;

    @Column(nullable = false)
    private boolean required = true;

    @Column(name = "max_price_atomic", nullable = false, precision = 39, scale = 0)
    private BigInteger maxPriceAtomic;

    @Column(name = "max_calls", nullable = false)
    private int maxCalls = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
