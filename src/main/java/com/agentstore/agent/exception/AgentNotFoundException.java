package com.agentstore.agent.exception;

import com.agentstore.common.exception.client.ClientException;
import com.agentstore.common.exception.constants.ErrorCode;

public final class AgentNotFoundException extends ClientException {
    public AgentNotFoundException() {
        super(ErrorCode.AGENT_NOT_FOUND);
    }
}
