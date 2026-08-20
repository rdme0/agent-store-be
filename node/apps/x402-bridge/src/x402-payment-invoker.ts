import {x402Client} from '@x402/core/client';
import type {PaymentRequirements} from '@x402/core/types';
import {decodePaymentResponseHeader, wrapFetchWithPayment} from '@x402/fetch';
import {ExactEvmScheme, getDefaultAsset} from '@x402/evm';
import {privateKeyToAccount} from 'viem/accounts';
import {Agent} from 'undici';
import type {BridgeAgentResponse, BridgePaymentRequest, BridgePaymentResponse} from '@agent-store/agent-protocol';
import {type EndpointGuard, EndpointPolicy} from './endpoint-policy.js';

const BASE_SEPOLIA = 'eip155:84532';
const MAX_RESPONSE_BYTES = 1_048_576;

export interface PaymentInvoker {
    payAndInvoke(request: BridgePaymentRequest): Promise<BridgePaymentResponse>;
}

export interface TimeoutScheduler {
    schedule(callback: () => void, timeoutMs: number): () => void;
}

const systemTimeoutScheduler: TimeoutScheduler = {
    schedule(callback, timeoutMs) {
        const timer = setTimeout(callback, timeoutMs);
        return () => clearTimeout(timer);
    }
};

export class X402PaymentInvoker implements PaymentInvoker {
    private readonly scheme: ExactEvmScheme;
    private readonly asset = getDefaultAsset(BASE_SEPOLIA).address;

    public constructor(privateKey: `0x${string}`, private readonly fetchImplementation: typeof fetch = globalThis.fetch, private readonly timeoutMs = 30_000, private readonly timeoutScheduler: TimeoutScheduler = systemTimeoutScheduler, private readonly endpointGuard: EndpointGuard = new EndpointPolicy(process.env.NODE_ENV === 'production' ? 'production' : 'development')) {
        this.scheme = new ExactEvmScheme(privateKeyToAccount(privateKey));
    }

    public async payAndInvoke(request: BridgePaymentRequest): Promise<BridgePaymentResponse> {
        const invalid = this.validateRequest(request);
        if (invalid) return invalid;
        let pinnedAddress: string;
        try {
            pinnedAddress = await this.endpointGuard.resolveAllowed(request.endpoint);
        } catch {
            return this.failure('DEFINITE_FAILURE', 'INVALID_ENDPOINT', 'Invocation endpoint violates bridge network policy');
        }
        let signatureCreated = false;
        let explicitPaymentFailure = false;
        const paymentClient = new x402Client((_version, accepts) => this.selectRequirement(request, accepts))
            .register(BASE_SEPOLIA, this.scheme)
            .onAfterPaymentCreation(async () => {
                signatureCreated = true;
            })
            .onPaymentResponse(async (context) => {
                explicitPaymentFailure = context.paymentRequired !== undefined || context.settleResponse?.success === false;
            });
        const controller = new AbortController();
        const dispatcher = new Agent({connect: {lookup: (_hostname, _options, callback) => callback(null, pinnedAddress, pinnedAddress.includes(':') ? 6 : 4)}});
        const cancelTimeout = this.timeoutScheduler.schedule(() => controller.abort(), this.timeoutMs);
        try {
            const response = await wrapFetchWithPayment(this.abortableFetch(controller, dispatcher), paymentClient)(request.endpoint, {
                method: request.method,
                headers: request.headers,
                body: request.body,
                signal: controller.signal
            });
            const receiptHeader = response.headers.get('payment-response');
            if (!receiptHeader) return this.failure(signatureCreated ? 'UNKNOWN_AFTER_SIGNATURE' : 'DEFINITE_FAILURE', 'FACILITATOR_ERROR', explicitPaymentFailure ? 'x402 settlement was rejected' : 'x402 settlement receipt is missing');
            let receipt: { success: boolean; transaction?: string; network?: string };
            try {
                receipt = decodePaymentResponseHeader(receiptHeader);
            } catch {
                return this.failure('UNKNOWN_AFTER_SIGNATURE', 'FACILITATOR_ERROR', 'x402 settlement receipt is invalid');
            }
            if (!receipt.success || !receipt.transaction || receipt.network !== BASE_SEPOLIA) return this.failure(explicitPaymentFailure ? 'DEFINITE_FAILURE' : 'UNKNOWN_AFTER_SIGNATURE', 'FACILITATOR_ERROR', 'x402 settlement was not successful');
            const agentResponse = await this.response(response);
            return {
                outcome: response.ok ? 'SETTLED' : 'PAID_INVOCATION_FAILED',
                transactionHash: receipt.transaction,
                paymentIdentifier: receipt.transaction,
                response: agentResponse
            };
        } catch (error) {
            return this.safeFailure(error, signatureCreated, explicitPaymentFailure);
        } finally {
            cancelTimeout();
            await dispatcher.close();
        }
    }

    private abortableFetch(controller: AbortController, dispatcher: Agent): typeof fetch {
        return async (input, init) => new Promise<Response>((resolve, reject) => {
            const abort = () => reject(new DOMException('Agent request aborted', 'AbortError'));
            if (controller.signal.aborted) return abort();
            controller.signal.addEventListener('abort', abort, {once: true});
            const endpoint = input instanceof Request ? input.url : input instanceof URL ? input.href : input;
            this.endpointGuard.assertAllowed(endpoint).then(() => this.fetchImplementation(input, {
                ...init,
                signal: controller.signal,
                redirect: 'error',
                dispatcher
            } as RequestInit)).then(resolve, reject).finally(() => controller.signal.removeEventListener('abort', abort));
        });
    }

    private validateRequest(request: BridgePaymentRequest): BridgePaymentResponse | undefined {
        if (!/^[1-9][0-9]*$/.test(request.amountAtomic) || (request.maxPriceAtomic !== undefined && (!/^[1-9][0-9]*$/.test(request.maxPriceAtomic) || BigInt(request.amountAtomic) > BigInt(request.maxPriceAtomic)))) return this.failure('DEFINITE_FAILURE', 'PRICE_MISMATCH', 'Payment amount does not match approved quote constraints');
        if (request.network !== BASE_SEPOLIA || request.asset.toLowerCase() !== this.asset.toLowerCase() || !/^0x[0-9a-f]{40}$/i.test(request.payTo)) return this.failure('DEFINITE_FAILURE', 'PRICE_MISMATCH', 'Payment terms do not match Base Sepolia USDC exact requirements');
        try {
            const url = new URL(request.endpoint);
            if (!['http:', 'https:'].includes(url.protocol)) throw new Error();
        } catch {
            return this.failure('DEFINITE_FAILURE', 'INVALID_ENDPOINT', 'Invocation endpoint must be an HTTP(S) URL');
        }
        if (!/^[A-Z]+$/.test(request.method)) return this.failure('DEFINITE_FAILURE', 'INVALID_METHOD', 'Invocation method must be uppercase HTTP token');
        return undefined;
    }

    private selectRequirement(request: BridgePaymentRequest, accepts: PaymentRequirements[]): PaymentRequirements {
        const selected = accepts.find((candidate) => candidate.scheme === 'exact' && candidate.network === BASE_SEPOLIA && candidate.amount === request.amountAtomic && candidate.asset.toLowerCase() === this.asset.toLowerCase() && candidate.asset.toLowerCase() === request.asset.toLowerCase() && candidate.payTo.toLowerCase() === request.payTo.toLowerCase() && (request.maxPriceAtomic === undefined || BigInt(candidate.amount) <= BigInt(request.maxPriceAtomic)));
        if (!selected) throw new Error('approved quote payment requirement mismatch');
        return selected;
    }

    private async response(response: Response): Promise<BridgeAgentResponse> {
        const body = await this.readBoundedBody(response);
        const headers: Record<string, string> = {};
        const contentType = response.headers.get('content-type');
        if (contentType !== null) headers['content-type'] = contentType;
        return {status: response.status, headers, body: body.toString('utf8')};
    }

    private async readBoundedBody(response: Response): Promise<Buffer> {
        const contentLength = response.headers.get('content-length');
        if (contentLength !== null && /^[0-9]+$/.test(contentLength) && BigInt(contentLength) > BigInt(MAX_RESPONSE_BYTES)) {
            await response.body?.cancel('agent response exceeds 1MB');
            throw new Error('agent response exceeds 1MB');
        }
        if (response.body === null) return Buffer.alloc(0);
        const reader = response.body.getReader();
        const chunks: Buffer[] = [];
        let received = 0;
        try {
            while (true) {
                const {done, value} = await reader.read();
                if (done) return Buffer.concat(chunks, received);
                received += value.byteLength;
                if (received > MAX_RESPONSE_BYTES) {
                    await reader.cancel('agent response exceeds 1MB');
                    throw new Error('agent response exceeds 1MB');
                }
                chunks.push(Buffer.from(value));
            }
        } finally {
            reader.releaseLock();
        }
    }

    private safeFailure(error: unknown, signatureCreated: boolean, explicitPaymentFailure: boolean): BridgePaymentResponse {
        const message = error instanceof Error ? error.message.toLowerCase() : '';
        if (message.includes('approved quote') || message.includes('payment requirement')) return this.failure('DEFINITE_FAILURE', 'PRICE_MISMATCH', 'Payment requirements do not match the approved quote');
        if (message.includes('insufficient') || message.includes('balance') || message.includes('funds')) return this.failure('DEFINITE_FAILURE', 'INSUFFICIENT_FUNDS', 'The payer wallet has insufficient funds');
        if (!signatureCreated) return this.failure('DEFINITE_FAILURE', message.includes('abort') ? 'AGENT_TIMEOUT' : 'FACILITATOR_ERROR', message.includes('abort') ? 'Agent request timed out before payment signing' : 'Agent payment requirements are invalid or unavailable');
        return this.failure(explicitPaymentFailure ? 'DEFINITE_FAILURE' : 'UNKNOWN_AFTER_SIGNATURE', 'FACILITATOR_ERROR', explicitPaymentFailure ? 'x402 settlement was rejected' : 'Paid x402 invocation outcome is unknown');
    }

    private failure(outcome: 'DEFINITE_FAILURE' | 'UNKNOWN_AFTER_SIGNATURE', code: string, message: string): BridgePaymentResponse {
        return {outcome, code, message};
    }
}
