package com.agentstore.agent.service

import com.agentstore.agent.model.entity.FunctionContract
import com.agentstore.common.exception.constants.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

interface FunctionContractReader {
    fun requireFunctionContract(id: UUID): FunctionContract

    fun validateInstance(schema: JsonNode, value: JsonNode, errorCode: ErrorCode)
}
