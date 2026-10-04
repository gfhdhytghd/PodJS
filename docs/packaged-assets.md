# Packaged project assets

An external project's `pod.config.json` can copy original files into its build output:

```json
{
  "resources": {
    "fontRegular": "assets/fonts/body.ttf",
    "assets": {
      "fonts/body.ttf": "assets/fonts/body.ttf",
      "licenses/body-font.txt": "assets/fonts/OFL.txt"
    }
  }
}
```

Each `resources.assets` key is a logical name relative to the reserved `pod-assets/` directory. Each value is a source file path resolved against the project directory; absolute source paths are also accepted. The CLI copies the original bytes into that target's own `dist` output and records their `size` and SHA-256 in `pod.manifest.json` under `assets`, keyed by the same logical name. Existing Android and Wear OS Gradle shells include the output directory as APK assets.

Logical names use `/`-separated ASCII segments starting with a letter or digit, followed by letters, digits, `.`, `_`, or `-`. The complete name is at most 512 characters and each segment at most 255. Empty segments, absolute paths, traversal, backslashes, trailing dots, Windows device names, case aliases, and file/directory name conflicts are rejected. Sources must be regular files. Source symlinks may resolve to regular files outside the generated tree; output paths and generated trees containing symlinks are rejected.

`pod-assets/` belongs to the build. On a successful update it contains only the currently configured assets, so removing a mapping removes its old generated copy. The CLI stages and hashes all copied bytes before replacing the old tree. Copy failures leave the previous tree intact, and handled publication failures attempt to restore it. Output directories require a single build writer; interruption between directory renames requires a subsequent successful build. Sources inside the tree being replaced are rejected to protect their original bytes. Unrelated output files are preserved. Projects without asset mappings or a prior generated tree create no new asset output, and an empty manifest asset section is omitted.

## Android packaged fonts

`TextFontOptions.fontAsset` selects a logical asset name for `textLayout.measure`, `textLayout.layout`, `textRaster.line`, and `SystemTextLine.options`:

```ts
const font = {
  fontAsset: "fonts/body.ttf",
  fontFaceIndex: 0,
  fontWeight: 500,
  italic: false,
  variationSettings: "'wght' 500",
};
```

The Android host loads `pod-assets/fonts/body.ttf` through its `AssetManager`. `fontAsset` and `fontPath` are mutually exclusive. The asset uses the same collection face, weight, italic, and variation settings as a file font. Missing, unreadable, or invalid fonts and invalid variation axes use the requested system fallback; malformed asset names and incompatible source options are errors. Packaged fonts share the bounded 32-entry native font cache, with the `AssetManager` identity included so different packages cannot reuse another package's typeface.

`fontPath` continues to select a font below the application's private `podjs/files` directory. `resources.fontRegular`, `fontBold`, and `fontSlots` configure atlas baking. A file can be used both for atlas baking and as a raw packaged asset by listing it in both settings.

Configuration, copy/hash identity, updates, removal, source preservation, failure rollback, and path boundaries have offline tests. The three Android font service tests passed on an API 30 watch: packaged variable font parameters match a direct Typeface builder, missing/invalid fonts retain fallback behavior, and private file fonts remain compatible. The file fixture uses target application storage and instrumentation APK assets; the first attempt incorrectly tried writing the instrumentation package's separate data directory. A production application reference also measured and rendered its original packaged font at matching native glyph bounds. These results do not establish every font, font scale or device's raster equivalence.
