package com.agentstore.agent.model.vo

enum class AgentListSort(val value: String) {
    NEWEST("newest"),
    NAME_ASC("name_asc"),
    ;

    companion object {
        fun from(value: String): AgentListSort {
            return entries.firstOrNull { sort -> sort.value == value }
                ?: throw IllegalArgumentException("지원하지 않는 정렬 기준입니다: $value")
        }
    }
}
