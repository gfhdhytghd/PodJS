import { test, expect } from 'bun:test';
import { createHash, createHmac, X509Certificate } from 'node:crypto';
import { createServer, connect as connectTls, type TLSSocket } from 'node:tls';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CompanionTlsConnector, companionCertificateDer } from '../platforms/harmony/companion/src/main/ets/CompanionTlsConnector';
class Port {
  sent: Uint8Array[] = []; closed = false; rejectTrust = false; connectCalls = 0;
  async connect(_host: string, _port: number, _ca: string, _timeout: number) { this.connectCalls++; if (this.rejectTrust) throw Error('CA rejected'); }
  listen() {}
  async certificateDer(): Promise<Uint8Array> { return new Uint8Array([48, 1, 2, 3]); }
  async send(bytes: Uint8Array) { this.sent.push(bytes.slice()); }
  close() { this.closed = true; }
}
const crypto = {
  async sha256(bytes: Uint8Array) { return new Uint8Array(createHash('sha256').update(bytes).digest()); },
  async hmacSha256(key: Uint8Array, bytes: Uint8Array) { return new Uint8Array(createHmac('sha256', key).update(bytes).digest()); }
};
const ca = '-----BEGIN CERTIFICATE-----\nMAECAw==\n-----END CERTIFICATE-----';
const pin = createHash('sha256').update(new Uint8Array([48, 1, 2, 3])).digest('hex');
const timer = { schedule(_ms: number, _expire: () => void) { return () => {}; } };
function connector(port: Port, fingerprint = pin) { return new CompanionTlsConnector({ create: () => port }, crypto, timer, '127.0.0.1', 8443, ca, fingerprint, 5000); }
test('CA and exact preapproved leaf pin gate all application writes', async () => {
  const port = new Port(), client = connector(port); const stream = await client.connect();
  expect(port.sent.length).toBe(0); await stream.write(new Uint8Array([1])); expect(port.sent[0]).toEqual(new Uint8Array([0, 0, 0, 1, 1]));
  stream.close(); expect(port.closed).toBe(true);
  for (const failure of ['pin', 'ca']) {
    const bad = new Port(); bad.rejectTrust = failure === 'ca';
    await expect(connector(bad, failure === 'pin' ? '0'.repeat(64) : pin).connect()).rejects.toThrow(failure === 'pin' ? 'fingerprint' : 'CA rejected');
    expect(bad.closed).toBe(true); expect(bad.sent.length).toBe(0); expect(bad.connectCalls).toBe(1);
  }
});
test('cancelled TLS verification cannot return a usable late stream', async () => {
  const port = new Port(); let finish!: (bytes: Uint8Array) => void;
  port.certificateDer = () => new Promise(resolve => { finish = resolve; });
  const client = connector(port), pending = client.connect();
  await Promise.resolve(); client.cancel(); finish(new Uint8Array([48, 1, 2, 3]));
  await expect(pending).rejects.toThrow('closed'); expect(port.sent.length).toBe(0);
});
test('DER/PEM fingerprints normalize identically and ambiguous or noncanonical PEM rejects', () => {
  const raw = new Uint8Array([48, 1, 2, 3]);
  expect(companionCertificateDer(new TextEncoder().encode(ca), 'pem')).toEqual(raw);
  expect(companionCertificateDer(raw, 'der')).toEqual(raw);
  for (const invalid of [ca + '\n' + ca, ca.replace('MAECAw==', 'MAECAx=='), ca.replace('MAECAw==', 'MAEC=w==')])
    expect(() => companionCertificateDer(new TextEncoder().encode(invalid), 'pem')).toThrow();
  expect(() => connector(new Port(), '')).toThrow('fingerprint');
});

test('real TLS loopback verifies CA plus leaf pin and refuses wrong trust before application bytes', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'podjs-tls-test-'));
  try {
    for (const name of ['server', 'untrusted']) {
      const generated = Bun.spawnSync(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-noenc', '-days', '1',
        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
        '-keyout', join(directory, name + '.key'), '-out', join(directory, name + '.pem')]);
      expect(generated.exitCode).toBe(0);
    }
    const pem = readFileSync(join(directory, 'server.pem'), 'utf8');
    const certificate = new X509Certificate(pem), leafPin = createHash('sha256').update(certificate.raw).digest('hex');
    let receivedBytes = 0;
    const server = createServer({ key: readFileSync(join(directory, 'server.key')), cert: pem, minVersion: 'TLSv1.2' }, socket => {
      socket.on('error', () => {});
      socket.on('data', data => { receivedBytes += data.length; socket.write(data); });
    });
    server.on('tlsClientError', () => {});
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    class RealPort {
      socket: TLSSocket | null = null; sent = 0;
      receive: (bytes: Uint8Array) => void = () => {};
      end: () => void = () => {};
      error: (error: Error) => void = () => {};
      listen(receive: (bytes: Uint8Array) => void, end: () => void, error: (error: Error) => void) { this.receive = receive; this.end = end; this.error = error; }
      async connect(host: string, port: number, ca: string, timeout: number) {
        await new Promise<void>((resolve, reject) => {
          const socket = connectTls({ host, port, ca, servername: 'localhost', rejectUnauthorized: true, minVersion: 'TLSv1.2', maxVersion: 'TLSv1.3' }, resolve);
          this.socket = socket;
          socket.setTimeout(timeout, () => socket.destroy(new Error('TLS timeout')));
          socket.on('data', bytes => this.receive(typeof bytes === 'string' ? Buffer.from(bytes) : bytes)); socket.on('end', () => this.end());
          socket.on('error', error => { reject(error); this.error(error); });
        });
      }
      async certificateDer(): Promise<Uint8Array> { return new Uint8Array(this.socket!.getPeerCertificate().raw); }
      async send(bytes: Uint8Array) { this.sent++; await new Promise<void>((resolve, reject) => this.socket!.write(bytes, error => error ? reject(error) : resolve())); }
      close() { this.socket?.destroy(); }
    }
    try {
      const address = server.address(); if (address === null || typeof address === 'string') throw Error('missing TLS port');
      const realTimer = { schedule(ms: number, expire: () => void) { const handle = setTimeout(expire, ms); return () => clearTimeout(handle); } };
      for (const mode of ['valid', 'wrong-pin', 'wrong-ca']) {
        const port = new RealPort();
        const trust = mode === 'wrong-ca' ? readFileSync(join(directory, 'untrusted.pem'), 'utf8') : pem;
        const connector = new CompanionTlsConnector({ create: () => port }, crypto, realTimer, '127.0.0.1', address.port,
          trust, mode === 'wrong-pin' ? '0'.repeat(64) : leafPin, 3000);
        const before = receivedBytes;
        try {
          if (mode === 'valid') {
            const stream = await connector.connect(); await stream.write(new Uint8Array([0, 128, 255]));
            expect(await stream.read()).toEqual(new Uint8Array([0, 128, 255]));
            expect(port.socket!.getProtocol()).toMatch(/^TLSv1\.[23]$/);
          } else {
            await expect(connector.connect()).rejects.toThrow();
            expect(port.sent).toBe(0); expect(receivedBytes).toBe(before);
          }
        } finally { connector.cancel(); }
      }
    } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
  } finally { rmSync(directory, { recursive: true, force: true }); }
}, 15000);
