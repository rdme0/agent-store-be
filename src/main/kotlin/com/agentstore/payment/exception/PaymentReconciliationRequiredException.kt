package com.agentstore.payment.exception

import com.agentstore.common.exception.client.ClientException
import com.agentstore.common.exception.constants.ErrorCode

class PaymentReconciliationRequiredException : ClientException(
    errorCode = ErrorCode.PAYMENT_RECONCILIATION_REQUIRED,
)
