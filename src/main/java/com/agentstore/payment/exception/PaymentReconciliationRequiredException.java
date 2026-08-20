package com.agentstore.payment.exception;

import com.agentstore.common.exception.client.ClientException;
import com.agentstore.common.exception.constants.ErrorCode;

public final class PaymentReconciliationRequiredException extends ClientException {
    public PaymentReconciliationRequiredException() {
        super(ErrorCode.PAYMENT_RECONCILIATION_REQUIRED);
    }
}
