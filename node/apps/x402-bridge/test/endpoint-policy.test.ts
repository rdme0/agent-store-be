import {describe, expect, it, vi} from 'vitest';
import {encodePaymentRequiredHeader, encodePaymentResponseHeader} from '@x402/core/http';
import {getDefaultAsset} from '@x402/evm';
import {EndpointPolicy} from '../src/endpoint-policy.js';
import {X402PaymentInvoker} from '../src/x402-payment-invoker.js';

describe('bridge endpoint policy', () => {
    it('allows only explicit loopback HTTP(S) endpoints in development', async () => {
        const policy = new EndpointPolicy('development');
        for (const endpoint of ['http://localhost:8090/invoke', 'https://127.0.0.1/invoke', 'http://[::1]/invoke']) await expect(policy.assertAllowed(endpoint)).resolves.toBeUndefined();
        for (const endpoint of ['http://10.0.0.1/invoke', 'https://8.8.8.8/invoke', 'http://127.1/invoke', 'http://2130706433/invoke', 'http://user:pass@127.0.0.1/invoke', 'ftp://localhost/file']) await expect(policy.assertAllowed(endpoint)).rejects.toThrow();
    });

    it('requires HTTPS and rejects private, link-local, loopback, multicast, unspecified, and reserved production addresses', async () => {
        const policy = new EndpointPolicy('production');
        await expect(policy.assertAllowed('https://8.8.8.8/invoke')).resolves.toBeUndefined();
        for (const endpoint of ['http://8.8.8.8/invoke', 'https://10.0.0.1/invoke', 'https://169.254.1.1/invoke', 'https://127.0.0.1/invoke', 'https://224.0.0.1/invoke', 'https://0.0.0.0/invoke', 'https://[::1]/invoke', 'https://[::ffff:127.0.0.1]/invoke', 'https://[::7f00:1]/invoke', 'https://[fe80::1]/invoke', 'https://[fc00::1]/invoke', 'https://[ff02::1]/invoke', 'https://[100::1]/invoke', 'https://[2002:7f00:1::1]/invoke', 'https://[2001:0::1]/invoke', 'https://[2001:db8::1]/invoke']) await expect(policy.assertAllowed(endpoint)).rejects.toThrow();
    });

    it('fails closed for DNS failure or mixed public/private records', async () => {
        const lookup = vi.fn().mockResolvedValueOnce([{address: '1.1.1.1'}]).mockResolvedValueOnce([{address: '1.1.1.1'}, {address: '10.0.0.1'}]).mockRejectedValueOnce(new Error('dns failed'));
        const policy = new EndpointPolicy('production', {lookup});
        await expect(policy.assertAllowed('https://public.test/invoke')).resolves.toBeUndefined();
        await expect(policy.assertAllowed('https://mixed.test/invoke')).rejects.toThrow('ENDPOINT_NOT_PUBLIC');
        await expect(policy.assertAllowed('https://unresolved.test/invoke')).rejects.toThrow('ENDPOINT_DNS_UNRESOLVED');
    });

    it('revalidates the endpoint before unpaid and paid outbound invocations', async () => {
        const guard = {
            assertAllowed: vi.fn().mockResolvedValue(undefined),
            resolveAllowed: vi.fn().mockResolvedValue('8.8.8.8')
        };
        const request = {
            paymentAttemptId: 'attempt-1',
            idempotencyKey: 'key-1',
            amountAtomic: '1000',
            maxPriceAtomic: '1000',
            network: 'eip155:84532' as const,
            asset: getDefaultAsset('eip155:84532').address,
            payTo: '0x0000000000000000000000000000000000000003',
            endpoint: 'https://agent.test/invoke',
            method: 'POST',
            body: '{}'
        };
        const requirement = encodePaymentRequiredHeader({
            x402Version: 2,
            resource: {url: request.endpoint},
            accepts: [{
                scheme: 'exact',
                network: request.network,
                amount: request.amountAtomic,
                asset: request.asset,
                payTo: request.payTo,
                maxTimeoutSeconds: 300,
                extra: {name: 'USDC', version: '2'}
            }]
        });
        const receipt = encodePaymentResponseHeader({success: true, transaction: '0xabc', network: request.network});
        const fetchMock = vi.fn().mockResolvedValueOnce(new Response('{}', {
            status: 402,
            headers: {'payment-required': requirement}
        })).mockResolvedValueOnce(new Response('{"ok":true}', {status: 200, headers: {'payment-response': receipt}}));
        const invoker = new X402PaymentInvoker(`0x${'11'.repeat(32)}` as `0x${string}`, fetchMock, 30_000, undefined, guard);
        await invoker.payAndInvoke(request);
        expect(guard.resolveAllowed).toHaveBeenCalledTimes(1);
        expect(guard.assertAllowed).toHaveBeenCalledTimes(2);
    });
});
