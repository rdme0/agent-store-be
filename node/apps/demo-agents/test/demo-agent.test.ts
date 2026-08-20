import {describe, expect, it, vi} from 'vitest';
import {decodePaymentRequiredHeader} from '@x402/core/http';
import {getDefaultAsset} from '@x402/evm';
import {buildDemoAgentApp, parseDemoAgentPort, parseDemoPaymentMode} from '../src/index.js';

describe('demo agents', () => {
    it('defaults to simulated mode and validates its listener port', () => {
        expect(parseDemoPaymentMode(undefined)).toBe('simulated');
        expect(parseDemoPaymentMode('x402')).toBe('x402');
        expect(() => parseDemoPaymentMode('live')).toThrow();
        expect(parseDemoAgentPort(undefined)).toBe(8090);
        expect(() => parseDemoAgentPort('0')).toThrow();
    });

    it('uses official x402 exact middleware only in x402 mode', async () => {
        const facilitator = {
            getSupported: vi.fn().mockResolvedValue({
                kinds: [{
                    x402Version: 2,
                    scheme: 'exact',
                    network: 'eip155:84532'
                }], extensions: [], signers: {}
            }), verify: vi.fn(), settle: vi.fn()
        };
        const app = buildDemoAgentApp({
            mode: 'x402',
            facilitatorUrl: 'https://facilitator.test',
            network: 'eip155:84532',
            agents: {investment: {amountAtomic: '1000', payTo: '0x0000000000000000000000000000000000000003'}},
            facilitatorClient: facilitator as never
        });
        try {
            const response = await app.inject({method: 'POST', url: '/agents/investment/invoke', payload: {}});
            expect(response.statusCode).toBe(402);
            expect(decodePaymentRequiredHeader(response.headers['payment-required'] as string).accepts[0]?.asset.toLowerCase()).toBe(getDefaultAsset('eip155:84532').address.toLowerCase());
        } finally {
            await app.close();
        }
    });

    it('propagates the invocation token to runtime callbacks', async () => {
        const app = buildDemoAgentApp();
        const original = globalThis.fetch;
        globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({output: {child: true}}), {status: 200}));
        try {
            const response = await app.inject({
                method: 'POST',
                url: '/agents/investment/invoke',
                headers: {authorization: 'Bearer signed-token'},
                payload: {
                    runtime: {
                        parentStepId: 'step-1',
                        callbackUrl: 'http://127.0.0.1:8080/runtime',
                        dependencies: [{agentVersionId: 'financial-v1', callPath: ['investment', 'financial']}]
                    }
                }
            });
            expect(response.statusCode).toBe(200);
            expect(response.json()).toMatchObject({dependencyResults: {financial: {child: true}}});
            expect(globalThis.fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/runtime', expect.objectContaining({
                headers: expect.objectContaining({authorization: 'Bearer signed-token'}),
                redirect: 'error'
            }));
        } finally {
            globalThis.fetch = original;
            await app.close();
        }
    });
});
