package com.agentstore.agent.model.entity;

import com.agentstore.dependency.model.entity.AgentDependency;
import com.agentstore.agent.model.vo.AgentVersionStatus;
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
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "agent_versions", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "semver"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentVersion {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(nullable = false, length = 32)
    private String semver;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "AgentVersionStatus")
    private AgentVersionStatus status = AgentVersionStatus.DRAFT;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "price_atomic", nullable = false, precision = 39, scale = 0)
    private BigInteger priceAtomic;

    @Column(nullable = false)
    private String network;

    @Column(nullable = false)
    private String asset;

    @Column(name = "pay_to", nullable = false)
    private String payTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "sourceVersion", cascade = CascadeType.ALL)
    private List<AgentDependency> dependencies = new ArrayList<>();
}
