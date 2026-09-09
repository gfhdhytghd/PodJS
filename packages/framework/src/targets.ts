export const POD_LOGICAL_VIEWPORT = Object.freeze({ width: 240, height: 240 });
export const POD_HOST_ABI = 2;
export const POD_MIN_HOST_ABI = 1;

export const PodCapability = Object.freeze({
  SyncState: "companion.sync.state",
  SyncMessage: "companion.sync.message",
  SyncFile: "companion.sync.file",
  LocalNotification: "notification.local",
  RemoteNotification: "notification.remote",
  ScheduledBackground: "background.scheduled",
  Accessibility: "accessibility.basic",
  HttpDownload: "net.download",
  Audio: "media.audio",
  Tts: "media.tts",
  Video: "media.video",
  Timer: "runtime.timer",
  Images: "data.images",
  BrowserAuth: "system.browser.auth",
  Sqlite: "data.sqlite",
  TextInput: "input.text",
  Secure: "data.secure",
  Crypto: "crypto.basic",
  FileChunks: "data.files.chunks",
  Touch: "input.touch",
  Rotary: "input.rotary",
  Back: "input.back",
  Network: "net.http",
  Files: "data.fs",
  Kv: "data.kv",
  Haptics: "device.haptics",
  Lifecycle: "host.lifecycle",
  Theme: "host.theme",
  RoundDisplay: "display.round",
  Browser: "system.browser",
} as const);

export type PodCapabilityId = (typeof PodCapability)[keyof typeof PodCapability];

export interface PodTargetProfile {
  readonly id: "android-watch" | "wearos-watch" | "watchos-watch" | "harmonyos-watch";
  readonly hostAbi: typeof POD_HOST_ABI;
  readonly logicalViewport: typeof POD_LOGICAL_VIEWPORT;
  readonly renderer: "vulkan-1.1" | "spritekit" | "gles3";
  readonly capabilities: readonly PodCapabilityId[];
}

const common = [
  PodCapability.Touch,
  PodCapability.Rotary,
  PodCapability.Kv,
  PodCapability.Haptics,
  PodCapability.Lifecycle,
  PodCapability.Theme,
  PodCapability.RoundDisplay,
] as const;

export const POD_TARGETS: Readonly<Record<PodTargetProfile["id"], PodTargetProfile>> = Object.freeze({
  "android-watch": {
    id: "android-watch",
    hostAbi: POD_HOST_ABI,
    logicalViewport: POD_LOGICAL_VIEWPORT,
    renderer: "vulkan-1.1",
capabilities: [...common, PodCapability.Back, PodCapability.Network, PodCapability.Files, PodCapability.Sqlite, PodCapability.TextInput, PodCapability.Secure, PodCapability.Crypto, PodCapability.FileChunks, PodCapability.Audio, PodCapability.Tts, PodCapability.Video, PodCapability.Timer, PodCapability.Images, PodCapability.BrowserAuth, PodCapability.HttpDownload, PodCapability.Browser, PodCapability.ScheduledBackground, PodCapability.LocalNotification, PodCapability.SyncState, PodCapability.SyncMessage, PodCapability.SyncFile],
  },
  "wearos-watch": {
    id: "wearos-watch",
    hostAbi: POD_HOST_ABI,
    logicalViewport: POD_LOGICAL_VIEWPORT,
    renderer: "vulkan-1.1",
    capabilities: [...common, PodCapability.Back, PodCapability.Network, PodCapability.Files, PodCapability.Sqlite, PodCapability.TextInput, PodCapability.Secure, PodCapability.Crypto, PodCapability.FileChunks, PodCapability.Audio, PodCapability.Tts, PodCapability.Video, PodCapability.Timer, PodCapability.Images, PodCapability.BrowserAuth, PodCapability.HttpDownload, PodCapability.Browser, PodCapability.ScheduledBackground, PodCapability.LocalNotification, PodCapability.SyncState, PodCapability.SyncMessage, PodCapability.SyncFile],
  },
  "watchos-watch": {
    id: "watchos-watch",
    hostAbi: POD_HOST_ABI,
    logicalViewport: POD_LOGICAL_VIEWPORT,
    renderer: "spritekit",
    capabilities: [...common, PodCapability.Network, PodCapability.Files],
  },
  "harmonyos-watch": {
    id: "harmonyos-watch",
    hostAbi: POD_HOST_ABI,
    logicalViewport: POD_LOGICAL_VIEWPORT,
    renderer: "gles3",
    capabilities: [...common, PodCapability.Back, PodCapability.Network, PodCapability.Files],
  },
});
