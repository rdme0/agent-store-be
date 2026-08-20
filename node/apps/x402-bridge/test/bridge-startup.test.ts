import {describe, expect, it} from 'vitest';
import {BRIDGE_HOST, bridgeStartupOptions, parseBridgePort} from '../src/index.js';

describe('x402 bridge startup boundary', () => {
    it('always binds the private bridge to IPv4 loopback', () => {
        expect(BRIDGE_HOST).toBe('127.0.0.1');
        expect(bridgeStartupOptions(undefined)).toEqual({host: '127.0.0.1', port: 8091});
        expect(bridgeStartupOptions('18091')).toEqual({host: '127.0.0.1', port: 18091});
    });

    it('rejects invalid listener ports instead of falling back to a public host', () => {
        for (const value of ['0', '65536', '8091.5', 'not-a-port']) expect(() => parseBridgePort(value)).toThrow('X402_BRIDGE_PORT must be an integer between 1 and 65535');
    });
});
