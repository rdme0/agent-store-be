package com.agentstore.agent.exception

import com.agentstore.common.exception.client.ClientException
import com.agentstore.common.exception.constants.ErrorCode

class AgentNotFoundException : ClientException(errorCode = ErrorCode.AGENT_NOT_FOUND)
