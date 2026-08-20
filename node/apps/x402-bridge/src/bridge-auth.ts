import {createHash, createHmac, timingSafeEqual} from 'node:crypto';

export const BRIDGE_AUTH_MAX_SKEW_MS = 60_000;
export const BRIDGE_BODY_LIMIT_BYTES = 1_048_576;

export interface BridgeAuthHeaders {
    timestamp: string;
    nonce: string;
    bodySha256: string;
    signature: string;
}

export function sha256(body: Buffer): string {
    return createHash('sha256').update(body).digest('hex');
}

export function canonicalBridgeRequest(method: string, path: string, headers: Omit<BridgeAuthHeaders, 'signature'>): string {
    return [method.toUpperCase(), path, headers.timestamp, headers.nonce, headers.bodySha256].join('\n');
}

export function signBridgeRequest(secret: string, method: string, path: string, timestamp: string, nonce: string, bodySha256: string): string {
    return createHmac('sha256', secret).update(canonicalBridgeRequest(method, path, {
        timestamp,
        nonce,
        bodySha256
    })).digest('hex');
}

export class NonceStore {
    private readonly nonces = new Map<string, number>();

    public claim(nonce: string, expiresAt: number, now: number): boolean {
        for (const [value, expiresAt] of this.nonces) if (expiresAt <= now) this.nonces.delete(value);
        if (this.nonces.has(nonce)) return false;
        this.nonces.set(nonce, expiresAt);
        return true;
    }
}

export function verifyBridgeAuth(input: {
    secret: string;
    method: string;
    path: string;
    body: Buffer;
    headers: Partial<BridgeAuthHeaders>;
    now: number;
    nonceStore: NonceStore;
}): string | undefined {
    const {timestamp, nonce, bodySha256, signature} = input.headers;
    if (!timestamp || !nonce || !bodySha256 || !signature) return 'BRIDGE_AUTH_REQUIRED';
    const timestampNumber = Number(timestamp);
    if (!Number.isSafeInteger(timestampNumber) || timestampNumber > Number.MAX_SAFE_INTEGER - BRIDGE_AUTH_MAX_SKEW_MS || Math.abs(input.now - timestampNumber) > BRIDGE_AUTH_MAX_SKEW_MS) return 'BRIDGE_AUTH_EXPIRED';
    if (!/^[a-f0-9]{64}$/i.test(bodySha256) || !/^[a-f0-9]{64}$/i.test(signature)) return 'BRIDGE_AUTH_INVALID';
    const actualHash = sha256(input.body);
    if (!timingSafeEqual(Buffer.from(actualHash, 'hex'), Buffer.from(bodySha256, 'hex'))) return 'BRIDGE_AUTH_INVALID';
    const expectedSignature = signBridgeRequest(input.secret, input.method, input.path, timestamp, nonce, bodySha256);
    if (!timingSafeEqual(Buffer.from(expectedSignature, 'hex'), Buffer.from(signature, 'hex'))) return 'BRIDGE_AUTH_INVALID';
    if (!input.nonceStore.claim(nonce, timestampNumber + BRIDGE_AUTH_MAX_SKEW_MS, input.now)) return 'BRIDGE_NONCE_REPLAY';
    return undefined;
}
