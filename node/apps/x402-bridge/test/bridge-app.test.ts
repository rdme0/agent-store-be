import {randomUUID} from 'node:crypto';
import {describe, expect, it, vi} from 'vitest';
import {sha256, signBridgeRequest} from '../src/bridge-auth.js';
import {buildBridgeApp} from '../src/bridge-app.js';

const secret = 'bridge-test-secret-12345';
const requestBody = {
    paymentAttemptId: 'attempt-1',
    idempotencyKey: 'key-1',
    amountAtomic: '1000',
    maxPriceAtomic: '1000',
    network: 'eip155:84532',
    asset: '0x0000000000000000000000000000000000000001',
    payTo: '0x0000000000000000000000000000000000000002',
    endpoint: 'http://127.0.0.1:8090/agents/investment/invoke',
    method: 'POST',
    body: '{}'
};

function headers(path: string, body: string, timestamp = '1000000', nonce = randomUUID()): Record<string, string> {
    const hash = sha256(Buffer.from(body));
    return {
        'content-type': 'application/json',
        'x-agentstore-timestamp': timestamp,
        'x-agentstore-nonce': nonce,
        'x-agentstore-body-sha256': hash,
        'x-agentstore-signature': signBridgeRequest(secret, 'POST', path, timestamp, nonce, hash)
    };
}

describe('x402 bridge authentication boundary', () => {
    it('requires a body-bound, fresh HMAC and invokes only once', async () => {
        const invoker = {
            payAndInvoke: vi.fn().mockResolvedValue({
                outcome: 'SETTLED',
                transactionHash: '0xabc',
                paymentIdentifier: '0xabc'
            })
        };
        const app = buildBridgeApp({secret, invoker, now: () => 1_000_000});
        const body = JSON.stringify(requestBody);
        const requestHeaders = headers('/internal/payments/pay-and-invoke', body);
        try {
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: requestHeaders,
                payload: body
            })).statusCode).toBe(200);
            expect(invoker.payAndInvoke).toHaveBeenCalledWith(requestBody);
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: requestHeaders,
                payload: body
            })).statusCode).toBe(409);
            expect(invoker.payAndInvoke).toHaveBeenCalledTimes(1);
        } finally {
            await app.close();
        }
    });

    it('retains a future-dated nonce through that request timestamp plus the allowed skew', async () => {
        let now = 1_000_000;
        const invoker = {payAndInvoke: vi.fn().mockResolvedValue({outcome: 'SETTLED'})};
        const app = buildBridgeApp({secret, invoker, now: () => now});
        const body = JSON.stringify(requestBody);
        const requestHeaders = headers('/internal/payments/pay-and-invoke', body, '1050000', '00000000-0000-4000-8000-000000000001');
        try {
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: requestHeaders,
                payload: body
            })).statusCode).toBe(200);
            now = 1_070_000;
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: requestHeaders,
                payload: body
            })).statusCode).toBe(409);
            expect(invoker.payAndInvoke).toHaveBeenCalledTimes(1);
        } finally {
            await app.close();
        }
    });

    it('rejects changed bodies, stale timestamps, and oversize payloads before payment', async () => {
        const invoker = {payAndInvoke: vi.fn()};
        const app = buildBridgeApp({secret, invoker, now: () => 1_000_000});
        const body = JSON.stringify(requestBody);
        try {
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: headers('/internal/payments/pay-and-invoke', body, '900000'),
                payload: body
            })).statusCode).toBe(401);
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: headers('/internal/payments/pay-and-invoke', body),
                payload: `${body} `
            })).statusCode).toBe(401);
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: {'content-type': 'application/json'},
                payload: 'x'.repeat(1_048_577)
            })).statusCode).toBe(413);
            expect(invoker.payAndInvoke).not.toHaveBeenCalled();
        } finally {
            await app.close();
        }
    });

    it('authenticates reconciliation but never initiates a payment', async () => {
        const invoker = {payAndInvoke: vi.fn()};
        const app = buildBridgeApp({secret, invoker, now: () => 1_000_000});
        const body = JSON.stringify({paymentAttemptId: 'attempt-1', idempotencyKey: 'key-1', transactionHash: '0xabc'});
        try {
            const response = await app.inject({
                method: 'POST',
                url: '/internal/payments/reconcile',
                headers: headers('/internal/payments/reconcile', body),
                payload: body
            });
            expect(response.statusCode).toBe(200);
            expect(response.json()).toEqual({status: 'UNKNOWN'});
            expect(invoker.payAndInvoke).not.toHaveBeenCalled();
        } finally {
            await app.close();
        }
    });

    it('reconciles only the exact in-memory settled attempt correlation and never invokes payment', async () => {
        const invoker = {
            payAndInvoke: vi.fn().mockResolvedValue({
                outcome: 'SETTLED',
                transactionHash: '0xtx',
                paymentIdentifier: 'payment-1',
                response: {status: 200, headers: {}, body: 'secret-body'}
            })
        };
        const app = buildBridgeApp({secret, invoker, now: () => 1_000_000});
        const payment = JSON.stringify(requestBody);
        const reconcile = JSON.stringify({
            paymentAttemptId: 'attempt-1',
            idempotencyKey: 'key-1',
            transactionHash: '0xtx',
            paymentIdentifier: 'payment-1'
        });
        try {
            await app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: headers('/internal/payments/pay-and-invoke', payment),
                payload: payment
            });
            const settled = await app.inject({
                method: 'POST',
                url: '/internal/payments/reconcile',
                headers: headers('/internal/payments/reconcile', reconcile),
                payload: reconcile
            });
            expect(settled.json()).toEqual({
                status: 'SETTLED',
                transactionHash: '0xtx',
                paymentIdentifier: 'payment-1'
            });
            const mismatch = JSON.stringify({paymentAttemptId: 'attempt-1', idempotencyKey: 'wrong-key'});
            expect((await app.inject({
                method: 'POST',
                url: '/internal/payments/reconcile',
                headers: headers('/internal/payments/reconcile', mismatch),
                payload: mismatch
            })).json()).toEqual({status: 'UNKNOWN'});
            expect(invoker.payAndInvoke).toHaveBeenCalledTimes(1);
        } finally {
            await app.close();
        }
    });

    it('shares one in-flight payment result across authenticated duplicate requests', async () => {
        let resolvePayment: ((value: {
            outcome: 'SETTLED';
            transactionHash: string;
            paymentIdentifier: string
        }) => void) | undefined;
        const invoker = {
            payAndInvoke: vi.fn(() => new Promise<{
                outcome: 'SETTLED';
                transactionHash: string;
                paymentIdentifier: string
            }>((resolve) => {
                resolvePayment = resolve;
            }))
        };
        const app = buildBridgeApp({secret, invoker, now: () => 1_000_000});
        const body = JSON.stringify(requestBody);
        try {
            const first = app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: headers('/internal/payments/pay-and-invoke', body, '1000000', '00000000-0000-4000-8000-000000000010'),
                payload: body
            });
            const second = app.inject({
                method: 'POST',
                url: '/internal/payments/pay-and-invoke',
                headers: headers('/internal/payments/pay-and-invoke', body, '1000000', '00000000-0000-4000-8000-000000000011'),
                payload: body
            });
            await vi.waitFor(() => expect(invoker.payAndInvoke).toHaveBeenCalledTimes(1));
            resolvePayment?.({outcome: 'SETTLED', transactionHash: '0xtx', paymentIdentifier: 'payment-1'});
            expect((await first).json()).toMatchObject({outcome: 'SETTLED', transactionHash: '0xtx'});
            expect((await second).json()).toMatchObject({outcome: 'SETTLED', transactionHash: '0xtx'});
            expect(invoker.payAndInvoke).toHaveBeenCalledTimes(1);
        } finally {
            await app.close();
        }
    });
});
