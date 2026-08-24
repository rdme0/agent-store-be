package com.agentstore.agent.model.vo

enum class AgentView(val value: String) {
    EASY("easy"),
    DEVELOPER("developer"),
    ;

    companion object {
        fun from(value: String): AgentView {
            return entries.firstOrNull { view -> view.value == value }
                ?: throw IllegalArgumentException("unsupported_agent_view")
        }
    }
}
