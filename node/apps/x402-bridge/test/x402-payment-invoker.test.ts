import {describe, expect, it, vi} from 'vitest';
import {encodePaymentRequiredHeader, encodePaymentResponseHeader} from '@x402/core/http';
import {getDefaultAsset} from '@x402/evm';
import {X402PaymentInvoker} from '../src/x402-payment-invoker.js';

const request = {
    paymentAttemptId: 'attempt-1',
    idempotencyKey: 'key-1',
    amountAtomic: '1000',
    maxPriceAtomic: '1000',
    network: 'eip155:84532' as const,
    asset: getDefaultAsset('eip155:84532').address,
    payTo: '0x0000000000000000000000000000000000000003',
    endpoint: 'http://127.0.0.1:8090/invoke',
    method: 'POST',
    body: '{}'
};
const key = `0x${'11'.repeat(32)}` as `0x${string}`;

function required(amount = '1000'): string {
    return encodePaymentRequiredHeader({
        x402Version: 2,
        resource: {url: request.endpoint},
        accepts: [{
            scheme: 'exact',
            network: 'eip155:84532',
            amount,
            asset: request.asset,
            payTo: request.payTo,
            maxTimeoutSeconds: 300,
            extra: {name: 'USDC', version: '2'}
        }]
    });
}

describe('X402PaymentInvoker', () => {
    it('returns a settlement only after exact Base Sepolia receipt and removes sensitive response headers', async () => {
        const fetchMock = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockResolvedValueOnce(new Response('{"ok":true}', {
            status: 200,
            headers: {
                'payment-response': encodePaymentResponseHeader({
                    success: true,
                    transaction: '0xabc',
                    network: 'eip155:84532'
                }),
                'set-cookie': 'secret',
                authorization: 'secret',
                'proxy-authenticate': 'secret',
                'content-type': 'application/json'
            }
        }));
        const result = await new X402PaymentInvoker(key, fetchMock).payAndInvoke(request);
        expect(result).toMatchObject({
            outcome: 'SETTLED',
            transactionHash: '0xabc',
            response: {status: 200, body: '{"ok":true}', headers: {'content-type': 'application/json'}}
        });
        expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({redirect: 'error'});
        expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({redirect: 'error'});
    });

    it('does not sign when advertised payment terms exceed the approved quote', async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response('{}', {
            status: 402,
            headers: {'payment-required': required('1001')}
        }));
        await expect(new X402PaymentInvoker(key, fetchMock).payAndInvoke(request)).resolves.toMatchObject({
            outcome: 'DEFINITE_FAILURE',
            code: 'PRICE_MISMATCH'
        });
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('preserves an actual payment when the agent response fails', async () => {
        const fetchMock = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockResolvedValueOnce(new Response('agent error', {
            status: 500,
            headers: {
                'payment-response': encodePaymentResponseHeader({
                    success: true,
                    transaction: '0xpaid',
                    network: 'eip155:84532'
                })
            }
        }));
        await expect(new X402PaymentInvoker(key, fetchMock).payAndInvoke(request)).resolves.toMatchObject({
            outcome: 'PAID_INVOCATION_FAILED',
            transactionHash: '0xpaid',
            response: {status: 500}
        });
    });

    it('accepts exactly 1MB streamed agent responses', async () => {
        const receipt = encodePaymentResponseHeader({success: true, transaction: '0xexact', network: 'eip155:84532'});
        const stream = new ReadableStream<Uint8Array>({
            start(controller) {
                controller.enqueue(new Uint8Array(1_048_576));
                controller.close();
            }
        });
        const exactCap = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockResolvedValueOnce(new Response(stream, {status: 200, headers: {'payment-response': receipt}}));
        await expect(new X402PaymentInvoker(key, exactCap).payAndInvoke(request)).resolves.toMatchObject({
            outcome: 'SETTLED',
            transactionHash: '0xexact',
            response: {body: expect.any(String)}
        });
    });

    it('reads a cap-crossing chunk only to detect overflow, then cancels without a subsequent read or response', async () => {
        const receipt = encodePaymentResponseHeader({success: true, transaction: '0xpaid', network: 'eip155:84532'});
        let cancelled = false;
        let pullCount = 0;
        const stream = new ReadableStream<Uint8Array>({
            pull(controller) {
                pullCount += 1;
                if (pullCount === 1) controller.enqueue(new Uint8Array(1_048_576));
                else if (pullCount === 2) controller.enqueue(new Uint8Array(1));
                else controller.enqueue(new Uint8Array(32));
            },
            cancel() {
                cancelled = true;
            }
        }, {highWaterMark: 0});
        const oversized = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockResolvedValueOnce(new Response(stream, {status: 200, headers: {'payment-response': receipt}}));
        await expect(new X402PaymentInvoker(key, oversized).payAndInvoke(request)).resolves.toMatchObject({outcome: 'UNKNOWN_AFTER_SIGNATURE'});
        expect(cancelled).toBe(true);
        expect(pullCount).toBe(2);
    });

    it('fast-rejects declared oversized responses and maps unavailable paid calls as unknown', async () => {
        const receipt = encodePaymentResponseHeader({success: true, transaction: '0xpaid', network: 'eip155:84532'});
        let cancelled = false;
        const stream = new ReadableStream<Uint8Array>({
            cancel() {
                cancelled = true;
            }
        });
        const declaredOversized = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockResolvedValueOnce(new Response(stream, {
            status: 200,
            headers: {'payment-response': receipt, 'content-length': '1048577'}
        }));
        await expect(new X402PaymentInvoker(key, declaredOversized).payAndInvoke(request)).resolves.toMatchObject({outcome: 'UNKNOWN_AFTER_SIGNATURE'});
        expect(cancelled).toBe(true);
        const unavailable = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': required()}
        })).mockRejectedValueOnce(new DOMException('unavailable', 'AbortError'));
        await expect(new X402PaymentInvoker(key, unavailable).payAndInvoke(request)).resolves.toMatchObject({outcome: 'UNKNOWN_AFTER_SIGNATURE'});
    });

    it('aborts one pending paid invocation at the controlled timeout and never retries it', async () => {
        let fireTimeout: (() => void) | undefined;
        let paidRequestStarted: (() => void) | undefined;
        const paidRequestStartedPromise = new Promise<void>((resolve) => {
            paidRequestStarted = resolve;
        });
        const pendingPaidResponse = new Promise<Response>(() => undefined);
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(new Response('{}', {status: 402, headers: {'payment-required': required()}}))
            .mockImplementationOnce(() => {
                paidRequestStarted?.();
                return pendingPaidResponse;
            });
        const scheduler = {
            schedule: vi.fn((callback: () => void) => {
                fireTimeout = callback;
                return vi.fn();
            })
        };
        const invocation = new X402PaymentInvoker(key, fetchMock, 30_000, scheduler).payAndInvoke(request);
        await paidRequestStartedPromise;
        fireTimeout?.();
        await expect(invocation).resolves.toMatchObject({
            outcome: 'UNKNOWN_AFTER_SIGNATURE',
            code: 'FACILITATOR_ERROR'
        });
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(scheduler.schedule).toHaveBeenCalledWith(expect.any(Function), 30_000);
    });
});
