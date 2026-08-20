package com.agentstore.payment.model.vo;

/**
 * The bridge can prove settlement, reject malformed reconciliation, or remain uncertain.
 */
public enum BridgeReconciliationStatus {
    SETTLED,
    DEFINITE_FAILURE,
    UNKNOWN
}
