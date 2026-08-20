import type {
    BridgePaymentRequest,
    BridgePaymentResponse,
    BridgeReconcileRequest,
    BridgeReconcileResponse
} from '@agent-store/agent-protocol';

type Entry = { state: 'IN_FLIGHT'; result: Promise<BridgePaymentResponse> } | {
    state: 'COMPLETED';
    response: BridgePaymentResponse
};

export class SettlementCorrelationRegistry {
    private readonly entries = new Map<string, Entry>();

    public claim(request: BridgePaymentRequest, execute: () => Promise<BridgePaymentResponse>): Promise<BridgePaymentResponse> {
        const key = this.key(request);
        const existing = this.entries.get(key);
        if (existing?.state === 'IN_FLIGHT') return existing.result;
        if (existing?.state === 'COMPLETED') return Promise.resolve(existing.response);
        const result = execute();
        this.entries.set(key, {state: 'IN_FLIGHT', result});
        result.then((response) => this.entries.set(key, {
            state: 'COMPLETED',
            response
        }), () => this.entries.delete(key));
        return result;
    }

    public reconcile(request: BridgeReconcileRequest): BridgeReconcileResponse {
        const entry = this.entries.get(this.key(request));
        const response = entry?.state === 'COMPLETED' ? entry.response : undefined;
        if (!response || response.outcome !== 'SETTLED' || !response.transactionHash || !response.paymentIdentifier || (request.transactionHash !== undefined && request.transactionHash !== response.transactionHash) || (request.paymentIdentifier !== undefined && request.paymentIdentifier !== response.paymentIdentifier)) return {status: 'UNKNOWN'};
        return {
            status: 'SETTLED',
            transactionHash: response.transactionHash,
            paymentIdentifier: response.paymentIdentifier
        };
    }

    private key(request: { paymentAttemptId: string; idempotencyKey: string }): string {
        return `${request.paymentAttemptId}\u0000${request.idempotencyKey}`;
    }
}
