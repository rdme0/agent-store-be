import {buildBridgeApp} from './bridge-app.js';
import {X402PaymentInvoker} from './x402-payment-invoker.js';

export {buildBridgeApp} from './bridge-app.js';
export {signBridgeRequest, sha256} from './bridge-auth.js';
export {X402PaymentInvoker} from './x402-payment-invoker.js';
export {EndpointPolicy} from './endpoint-policy.js';

export const BRIDGE_HOST = '127.0.0.1';

export function parseBridgePort(value: string | undefined): number {
    if (value === undefined || value === '') return 8091;
    if (!/^[0-9]+$/.test(value) || Number(value) < 1 || Number(value) > 65535) throw new Error('X402_BRIDGE_PORT must be an integer between 1 and 65535');
    return Number(value);
}

export function bridgeStartupOptions(portValue: string | undefined): { host: typeof BRIDGE_HOST; port: number } {
    return {host: BRIDGE_HOST, port: parseBridgePort(portValue)};
}

const invokedFile = process.argv[1];
if (invokedFile?.endsWith('index.ts') || invokedFile?.endsWith('index.js')) {
    const secret = process.env.X402_BRIDGE_SECRET;
    const privateKey = process.env.X402_PRIVATE_KEY;
    if (!secret || !privateKey || !/^0x[0-9a-fA-F]{64}$/.test(privateKey)) throw new Error('X402_BRIDGE_SECRET and a valid X402_PRIVATE_KEY are required');
    const app = buildBridgeApp({secret, invoker: new X402PaymentInvoker(privateKey as `0x${string}`)});
    await app.listen(bridgeStartupOptions(process.env.X402_BRIDGE_PORT));
}
