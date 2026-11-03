# Optional P-256 dependency

`noble-p256.js` bundles MIT-licensed `@noble/curves@1.9.7` and its
`@noble/hashes@1.8.0` dependency. The license texts accompany the bundle.
The module-local UTF-8 encoder (`noble-text-encoder.js`) is the existing portable
encoder used by the reference mini-program crypto build. It changes no globals.

The public API is `@podjs/framework/p256`; the generated bundle is an internal
implementation detail. Do not export it from the root or `./crypto` entry.
The existing `createCrypto(request).verifyP256` continues to use the native host.

Rebuild using an existing node_modules containing these exact noble versions
and esbuild. This command does not install packages:

```sh
bun scripts/build-p256.ts /path/to/node_modules
```

The rebuilt framework bundle has SHA256
`f54970998a152b8c09c5f1780f38bfab6be25eb204e5f56459b53a9d878ce802`.
The UTF-8 encoder has SHA256
`6200d557f64d2cb86582d1c793a66e2452ed1df4df8a310fe2f2763c9363124e`.
