import {randomUUID} from 'node:crypto';
import Fastify, {type FastifyInstance} from 'fastify';
import {type FacilitatorClient, HTTPFacilitatorClient} from '@x402/core/server';
import {ExactEvmScheme} from '@x402/evm/exact/server';
import {getDefaultAsset} from '@x402/evm';
import {paymentMiddleware, x402ResourceServer} from '@x402/fastify';
import type {AgentManifest} from '@agent-store/agent-protocol';
import {agentProtocolVersion} from '@agent-store/agent-protocol';
import {Agent} from 'undici';

export const demoAgentManifest: AgentManifest = {
    protocolVersion: agentProtocolVersion,
    name: 'demo-agent',
    version: '0.1.0',
    description: 'A fixture-only agent for AgentStore local development.',
    capabilities: [{name: 'demo', description: 'Returns deterministic fixture data.'}]
};

export const demoAgentFixtures: Record<string, Record<string, unknown>> = {
    investment: {recommendation: 'balanced', score: 0.82},
    financial: {revenueGrowth: 0.14, debtRatio: 0.31},
    news: {sentiment: 'positive', articles: 12},
    risk: {riskLevel: 'medium', volatility: 0.18}
};

interface RuntimeDependency {
    agentVersionId: string;
    callPath: string[];
    input?: unknown;
}

interface DemoInvocationBody {
    input?: unknown;
    runtime?: { parentStepId: string; callbackUrl: string; dependencies: RuntimeDependency[]; };
}

export interface DemoAgentPaymentConfig {
    mode: 'simulated' | 'x402';
    facilitatorUrl?: string;
    network?: 'eip155:84532';
    agents?: Record<string, { amountAtomic: string; asset?: string; payTo: string }>;
    syncFacilitatorOnStart?: boolean;
    facilitatorClient?: FacilitatorClient;
}

export function parseDemoPaymentMode(value: unknown): 'simulated' | 'x402' {
    if (value === undefined || value === '') return 'simulated';
    if (value === 'simulated' || value === 'x402') return value;
    throw new Error('DEMO_PAYMENT_MODE must be simulated or x402');
}

export function parseDemoAgentPort(value: unknown): number {
    if (value === undefined || value === '') return 8090;
    if (typeof value !== 'string' || !/^[0-9]+$/.test(value) || Number(value) < 1 || Number(value) > 65535) throw new Error('DEMO_AGENT_PORT must be an integer between 1 and 65535');
    return Number(value);
}

async function invokeRuntimeCallback(callbackUrl: string, authorization: string, payload: unknown): Promise<Response> {
    const url = new URL(callbackUrl);
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || url.username || url.password || url.hash) {
        throw new Error('runtime callback URL must be an HTTP loopback URL');
    }
    const pinnedAddress = url.hostname === 'localhost' ? '127.0.0.1' : url.hostname;
    const dispatcher = new Agent({connect: {lookup: (_hostname, _options, callback) => callback(null, pinnedAddress, 4)}});
    try {
        const response = await fetch(callbackUrl, {
            method: 'POST', redirect: 'error',
            headers: {'content-type': 'application/json', authorization, 'idempotency-key': randomUUID()},
            body: JSON.stringify(payload), signal: AbortSignal.timeout(30_000), dispatcher
        } as RequestInit);
        const length = response.headers.get('content-length');
        if (length !== null && (!/^\d+$/.test(length) || Number(length) > 1_048_576)) throw new Error('runtime callback response exceeds 1MB');
        return response;
    } finally {
        await dispatcher.close();
    }
}

async function boundedJson(response: Response): Promise<{ output?: unknown }> {
    if (response.body === null) return {};
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let received = 0;
    try {
        while (true) {
            const {done, value} = await reader.read();
            if (done) return JSON.parse(new TextDecoder().decode(Buffer.concat(chunks))) as { output?: unknown };
            received += value.byteLength;
            if (received > 1_048_576) {
                await reader.cancel('runtime callback response exceeds 1MB');
                throw new Error('runtime callback response exceeds 1MB');
            }
            chunks.push(value);
        }
    } finally {
        reader.releaseLock();
    }
}

export function buildDemoAgentApp(paymentConfig: DemoAgentPaymentConfig = {mode: 'simulated'}): FastifyInstance {
    const app = Fastify({logger: false, bodyLimit: 1_048_576});
    app.get('/health', async () => ({status: 'ok'}));
    if (paymentConfig.mode === 'x402') configureX402(app, paymentConfig);
    app.post<{ Params: { agent: string }; Body: DemoInvocationBody }>('/agents/:agent/invoke', async (request) => {
        const dependencyResults: Record<string, unknown> = {};
        if (request.body.runtime) {
            for (const dependency of request.body.runtime.dependencies) {
                const response = await invokeRuntimeCallback(request.body.runtime.callbackUrl, request.headers.authorization ?? '', {
                    parentStepId: request.body.runtime.parentStepId,
                    agentVersionId: dependency.agentVersionId,
                    callPath: dependency.callPath,
                    input: dependency.input
                });
                if (!response.ok) throw new Error(`runtime callback failed: ${response.status}`);
                const slug = dependency.callPath.at(-1);
                if (slug) dependencyResults[slug] = (await boundedJson(response)).output;
            }
        }
        return {
            agent: request.params.agent,
            output: demoAgentFixtures[request.params.agent] ?? {status: 'unknown-agent'},
            dependencyResults
        };
    });
    return app;
}

function configureX402(app: FastifyInstance, config: DemoAgentPaymentConfig): void {
    if (!config.facilitatorUrl || config.network !== 'eip155:84532' || !config.agents) throw new Error('Complete x402 demo-agent payment configuration is required');
    const asset = getDefaultAsset(config.network).address;
    for (const terms of Object.values(config.agents)) if (!/^[1-9][0-9]*$/.test(terms.amountAtomic) || (terms.asset !== undefined && terms.asset.toLowerCase() !== asset.toLowerCase()) || !/^0x[0-9a-f]{40}$/i.test(terms.payTo)) throw new Error('Demo-agent x402 amount, official Base Sepolia USDC asset and payTo are required');
    const facilitator = config.facilitatorClient ?? new HTTPFacilitatorClient({url: config.facilitatorUrl});
    const resourceServer = new x402ResourceServer(facilitator).register(config.network, new ExactEvmScheme());
    const routes = Object.fromEntries(Object.entries(config.agents).map(([slug, terms]) => [`POST /agents/${slug}/invoke`, {
        accepts: {
            scheme: 'exact',
            network: config.network,
            payTo: terms.payTo,
            price: {amount: terms.amountAtomic, asset}
        }, description: `${slug} demo agent invocation`, mimeType: 'application/json'
    }]));
    paymentMiddleware(app, routes as never, resourceServer, undefined, undefined, config.syncFacilitatorOnStart ?? true);
}

const invokedFile = process.argv[1];
if (invokedFile?.endsWith('index.ts') || invokedFile?.endsWith('index.js')) {
    const mode = parseDemoPaymentMode(process.env.DEMO_PAYMENT_MODE);
    const agents = Object.fromEntries(['investment', 'financial', 'news', 'risk'].map((slug) => {
        const prefix = `DEMO_${slug.toUpperCase()}`;
        return [slug, {
            amountAtomic: process.env[`${prefix}_PRICE_ATOMIC`] ?? '',
            asset: process.env[`${prefix}_ASSET`],
            payTo: process.env[`${prefix}_PAY_TO`] ?? ''
        }];
    }));
    const app = buildDemoAgentApp(mode === 'x402' ? {
        mode,
        facilitatorUrl: process.env.X402_FACILITATOR_URL,
        network: 'eip155:84532',
        agents
    } : {mode});
    await app.listen({
        host: process.env.DEMO_AGENT_HOST ?? '127.0.0.1',
        port: parseDemoAgentPort(process.env.DEMO_AGENT_PORT)
    });
}
