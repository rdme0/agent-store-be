import {lookup as dnsLookup} from 'node:dns/promises';
import {isIP} from 'node:net';

export type BridgeEnvironment = 'development' | 'production';

export interface EndpointResolver {
    lookup(hostname: string): Promise<Array<{ address: string }>>;
}

export interface EndpointGuard {
    assertAllowed(endpoint: string): Promise<void>;

    resolveAllowed(endpoint: string): Promise<string>;
}

const resolver: EndpointResolver = {lookup: (hostname) => dnsLookup(hostname, {all: true, verbatim: true})};

export class EndpointPolicy implements EndpointGuard {
    public constructor(private readonly environment: BridgeEnvironment, private readonly endpointResolver: EndpointResolver = resolver) {
    }

    public async assertAllowed(endpoint: string): Promise<void> {
        await this.resolveAllowed(endpoint);
    }

    public async resolveAllowed(endpoint: string): Promise<string> {
        let url: URL;
        try {
            url = new URL(endpoint);
        } catch {
            throw new Error('INVALID_ENDPOINT');
        }
        if (url.username || url.password) throw new Error('ENDPOINT_CREDENTIALS_FORBIDDEN');
        const host = url.hostname.replace(/^\[|\]$/g, '').toLowerCase();
        if (this.environment === 'development') {
            if (!['localhost', '127.0.0.1', '::1'].includes(host) || !['http:', 'https:'].includes(url.protocol) || !isExplicitDevelopmentLoopback(endpoint, host)) throw new Error('ENDPOINT_NOT_LOOPBACK');
            return host === 'localhost' ? '127.0.0.1' : host;
        }
        if (url.protocol !== 'https:') throw new Error('ENDPOINT_HTTPS_REQUIRED');
        const family = isIP(host);
        if (family !== 0) {
            if (!isPublicAddress(host)) throw new Error('ENDPOINT_NOT_PUBLIC');
            return host;
        }
        let addresses: Array<{ address: string }>;
        try {
            addresses = await this.endpointResolver.lookup(host);
        } catch {
            throw new Error('ENDPOINT_DNS_UNRESOLVED');
        }
        if (addresses.length === 0 || addresses.some(({address}) => !isPublicAddress(address))) throw new Error('ENDPOINT_NOT_PUBLIC');
        return addresses[0]!.address;
    }
}

function isPublicAddress(address: string): boolean {
    const family = isIP(address);
    if (family === 4) return isPublicIpv4(address);
    if (family === 6) return isPublicIpv6(address);
    return false;
}

function isPublicIpv4(address: string): boolean {
    const [a, b] = address.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && (b === 0 || b === 168)) || (a === 192 && b === 88) || (a === 192 && b === 2) || (a === 198 && (b === 18 || b === 19 || b === 51)) || (a === 203 && b === 0) || a >= 224);
}

function isPublicIpv6(address: string): boolean {
    const normalized = address.toLowerCase();
    if (normalized.startsWith('::ffff:')) return false;
    if (normalized.startsWith('::') || normalized.startsWith('100:') || normalized.startsWith('fc') || normalized.startsWith('fd') || normalized.startsWith('fe8') || normalized.startsWith('fe9') || normalized.startsWith('fea') || normalized.startsWith('feb') || normalized.startsWith('ff') || /^2001:0*:/.test(normalized) || normalized.startsWith('2001:db8')) return false;
    const sixToFour = /^2002:([0-9a-f]{1,4}):([0-9a-f]{1,4}):/i.exec(normalized);
    if (sixToFour) {
        const first = Number.parseInt(sixToFour[1], 16);
        const second = Number.parseInt(sixToFour[2], 16);
        return isPublicIpv4([first >> 8, first & 255, second >> 8, second & 255].join('.'));
    }
    return true;
}

function isExplicitDevelopmentLoopback(endpoint: string, host: string): boolean {
    const authority = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]*)/i.exec(endpoint)?.[1]?.replace(/:\d+$/, '').toLowerCase();
    return authority === host || (host === '::1' && authority === '[::1]');
}
