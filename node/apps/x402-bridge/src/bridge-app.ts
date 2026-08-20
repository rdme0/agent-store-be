import Fastify, {type FastifyInstance, type FastifyRequest} from 'fastify';
import type {BridgePaymentRequest, BridgeReconcileRequest} from '@agent-store/agent-protocol';
import {BRIDGE_BODY_LIMIT_BYTES, NonceStore, verifyBridgeAuth} from './bridge-auth.js';
import type {PaymentInvoker} from './x402-payment-invoker.js';
import {SettlementCorrelationRegistry} from './settlement-correlation-registry.js';

export interface BridgeConfiguration {
    secret: string;
    invoker: PaymentInvoker;
    now?: () => number;
}

interface RawRequest extends FastifyRequest {
    body: Buffer;
}

export function buildBridgeApp(configuration: BridgeConfiguration): FastifyInstance {
    if (configuration.secret.length < 16) throw new Error('X402_BRIDGE_SECRET must be at least 16 characters');
    const app = Fastify({logger: false, bodyLimit: BRIDGE_BODY_LIMIT_BYTES});
    const nonceStore = new NonceStore();
    const now = configuration.now ?? Date.now;
    const correlations = new SettlementCorrelationRegistry();
    app.removeContentTypeParser('application/json');
    app.addContentTypeParser('application/json', {parseAs: 'buffer'}, (_request, payload, done) => done(null, payload));
    app.setErrorHandler((error, _request, reply) => {
        if ((error as {
            code?: string
        }).code === 'FST_ERR_CTP_BODY_TOO_LARGE') return reply.code(413).send({
            code: 'REQUEST_BODY_TOO_LARGE',
            message: 'Bridge request body must not exceed 1MB'
        });
        return reply.code(400).send({code: 'INVALID_REQUEST', message: 'Bridge request is invalid'});
    });

    app.get('/health', async () => ({status: 'ok', service: 'x402-bridge'}));
    app.post('/internal/payments/pay-and-invoke', async (request, reply) => {
        const raw = request as RawRequest;
        const error = authenticate(raw, configuration.secret, nonceStore, now());
        if (error) return reply.code(error === 'BRIDGE_NONCE_REPLAY' ? 409 : 401).send({
            code: error,
            message: 'Bridge authentication failed'
        });
        const parsed = parseJson<BridgePaymentRequest>(raw.body);
        if (!parsed || !validPaymentRequest(parsed)) return reply.code(400).send({
            code: 'INVALID_PAYMENT_REQUEST',
            message: 'Payment invocation request is invalid'
        });
        const result = await correlations.claim(parsed, () => configuration.invoker.payAndInvoke(parsed));
        return reply.send(result);
    });
    app.post('/internal/payments/reconcile', async (request, reply) => {
        const raw = request as RawRequest;
        const error = authenticate(raw, configuration.secret, nonceStore, now());
        if (error) return reply.code(error === 'BRIDGE_NONCE_REPLAY' ? 409 : 401).send({
            code: error,
            message: 'Bridge authentication failed'
        });
        const parsed = parseJson<BridgeReconcileRequest>(raw.body);
        if (!parsed || !validReconcileRequest(parsed)) return reply.code(400).send({
            code: 'INVALID_RECONCILE_REQUEST',
            message: 'Payment reconciliation request is invalid'
        });
        return reply.send(correlations.reconcile(parsed));
    });
    return app;
}

function authenticate(request: RawRequest, secret: string, nonceStore: NonceStore, now: number): string | undefined {
    return verifyBridgeAuth({
        secret,
        method: request.method,
        path: request.routeOptions.url ?? request.url.split('?')[0] ?? '/',
        body: request.body,
        headers: {
            timestamp: header(request, 'x-agentstore-timestamp'),
            nonce: header(request, 'x-agentstore-nonce'),
            bodySha256: header(request, 'x-agentstore-body-sha256'),
            signature: header(request, 'x-agentstore-signature')
        },
        now,
        nonceStore
    });
}

function header(request: FastifyRequest, name: string): string | undefined {
    const value = request.headers[name];
    return typeof value === 'string' ? value : undefined;
}

function parseJson<T>(body: Buffer): T | undefined {
    try {
        return JSON.parse(body.toString('utf8')) as T;
    } catch {
        return undefined;
    }
}

function validPaymentRequest(value: BridgePaymentRequest): boolean {
    return typeof value.paymentAttemptId === 'string' && value.paymentAttemptId.length > 0
        && typeof value.idempotencyKey === 'string' && value.idempotencyKey.length > 0
        && typeof value.amountAtomic === 'string' && typeof value.network === 'string'
        && typeof value.asset === 'string' && typeof value.payTo === 'string'
        && typeof value.endpoint === 'string' && typeof value.method === 'string'
        && (value.maxPriceAtomic === undefined || typeof value.maxPriceAtomic === 'string')
        && (value.headers === undefined || Object.values(value.headers).every((headerValue) => typeof headerValue === 'string'))
        && (value.body === undefined || typeof value.body === 'string');
}

function validReconcileRequest(value: BridgeReconcileRequest): boolean {
    return typeof value.paymentAttemptId === 'string' && value.paymentAttemptId.length > 0
        && typeof value.idempotencyKey === 'string' && value.idempotencyKey.length > 0
        && (value.transactionHash === undefined || typeof value.transactionHash === 'string')
        && (value.paymentIdentifier === undefined || typeof value.paymentIdentifier === 'string');
}
