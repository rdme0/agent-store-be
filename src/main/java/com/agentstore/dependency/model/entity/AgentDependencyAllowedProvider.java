package com.agentstore.dependency.model.entity;

import com.agentstore.common.model.entity.BaseEntity;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "agent_dependency_allowed_providers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentDependencyAllowedProvider extends BaseEntity {

    @EmbeddedId
    private AgentDependencyAllowedProviderId id;

    public AgentDependencyAllowedProvider(UUID dependencyId, UUID agentId) {
        this.id = new AgentDependencyAllowedProviderId(dependencyId, agentId);
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class AgentDependencyAllowedProviderId implements Serializable {

        private UUID dependencyId;
        private UUID agentId;

        public AgentDependencyAllowedProviderId(UUID dependencyId, UUID agentId) {
            this.dependencyId = dependencyId;
            this.agentId = agentId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AgentDependencyAllowedProviderId)) {
                return false;
            }
            AgentDependencyAllowedProviderId that = (AgentDependencyAllowedProviderId) other;
            return Objects.equals(dependencyId, that.dependencyId)
                    && Objects.equals(agentId, that.agentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dependencyId, agentId);
        }
    }
}
