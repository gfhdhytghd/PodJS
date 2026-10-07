# Your watch app

Run `bun install`, `bun run typecheck`, `bun run test`, then `bun run build`.
`bun run pod -- package --target=android-watch` builds native packages; it does
not install them. See the framework's `docs/getting-started.md` for prerequisites,
release signing and outputs. This project's file dependencies and CLI wrapper
point to the checkout that created it. Regenerate or update those paths when
moving the project or the framework. Keep credentials outside source control.
