export const agentProtocolVersion = '0.1.0';

export interface AgentManifest {
    protocolVersion: typeof agentProtocolVersion;
    name: string;
    version: string;
    description: string;
    capabilities: Array<{ name: string; description: string }>;
}

export type BridgePaymentOutcome =
    'SETTLED'
    | 'DEFINITE_FAILURE'
    | 'UNKNOWN_AFTER_SIGNATURE'
    | 'PAID_INVOCATION_FAILED';

export interface BridgePaymentRequest {
    paymentAttemptId: string;
    idempotencyKey: string;
    amountAtomic: string;
    maxPriceAtomic?: string;
    network: 'eip155:84532';
    asset: string;
    payTo: string;
    endpoint: string;
    method: string;
    headers?: Record<string, string>;
    body?: string;
}

export interface BridgeAgentResponse {
    status: number;
    headers: Record<string, string>;
    body: string;
}

export interface BridgePaymentResponse {
    outcome: BridgePaymentOutcome;
    code?: string;
    message?: string;
    transactionHash?: string;
    paymentIdentifier?: string;
    response?: BridgeAgentResponse;
}

export interface BridgeReconcileRequest {
    paymentAttemptId: string;
    idempotencyKey: string;
    transactionHash?: string;
    paymentIdentifier?: string;
}

export type BridgeReconcileResponse =
    | { status: 'SETTLED'; transactionHash: string; paymentIdentifier?: string }
    | { status: 'NOT_FOUND' }
    | { status: 'UNKNOWN'; transactionHash?: string; paymentIdentifier?: string };
