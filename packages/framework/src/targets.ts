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
  Rfcomm: "net.rfcomm",
  Tcp: "net.tcp",
  WebSocket: "net.websocket",
  Discovery: "net.discovery",
  BleProvision: "net.ble.provision",
  ByteStream: "net.stream",
  Audio: "media.audio",
  DebugAcoustic: "debug.acoustic",
  Tts: "media.tts",
  TtsSpectrum: "media.tts.spectrum",
  Video: "media.video",
  VideoSessions: "media.video.sessions",
  Timer: "runtime.timer",
  DisplayPower: "display.power",
  Toast: "system.toast",
  Images: "data.images",
  BrowserChallenge: "system.browser.challenge",
  BrowserAuth: "system.browser.auth",
  BrowserAuthStatus: "system.browser.auth.status",
  Sqlite: "data.sqlite",
  TextLayout: "text.layout",
  TextRaster: "text.raster",
  TextEnvironment: "text.environment",
  TextInput: "input.text",
  TextInputState: "input.text.state",
  TimeInput: "input.time",
  Location: "device.location",
  Battery: "device.battery",
  DeviceInfo: "device.info",
  Secure: "data.secure",
  Crypto: "crypto.basic",
  FileChunks: "data.files.chunks",
  Uri: "data.uri",
  Touch: "input.touch",
  Rotary: "input.rotary",
  Back: "input.back",
  Network: "net.http",
  Files: "data.fs",
  Kv: "data.kv",
  Haptics: "device.haptics",
  Lifecycle: "host.lifecycle",
  Takeover: "host.takeover",
  Theme: "host.theme",
  RoundDisplay: "display.round",
  Browser: "system.browser",
  BrowserSiteData: "system.browser.siteData",
  BrowserAvailability: "system.browser.availability",
  BrowserInApp: "system.browser.inApp",
  BrowserInAppState: "system.browser.inApp.state",
  BrowserInAppTheme: "system.browser.inApp.theme",
  Share: "system.share",
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
capabilities: [...common, PodCapability.Back, PodCapability.Network, PodCapability.Files, PodCapability.Sqlite, PodCapability.TextLayout, PodCapability.TextRaster, PodCapability.TextEnvironment, PodCapability.TextInput, PodCapability.TextInputState, PodCapability.TimeInput, PodCapability.Location, PodCapability.Battery, PodCapability.DeviceInfo, PodCapability.Secure, PodCapability.Crypto, PodCapability.FileChunks, PodCapability.Uri, PodCapability.Audio, PodCapability.DebugAcoustic, PodCapability.Tts, PodCapability.TtsSpectrum, PodCapability.Video, PodCapability.VideoSessions, PodCapability.Timer, PodCapability.DisplayPower, PodCapability.Toast, PodCapability.Images, PodCapability.BrowserChallenge, PodCapability.BrowserAuth, PodCapability.BrowserAuthStatus, PodCapability.HttpDownload, PodCapability.Rfcomm, PodCapability.Tcp, PodCapability.ByteStream, PodCapability.WebSocket, PodCapability.Discovery, PodCapability.BleProvision, PodCapability.Browser, PodCapability.BrowserSiteData, PodCapability.BrowserAvailability, PodCapability.BrowserInApp, PodCapability.BrowserInAppState, PodCapability.BrowserInAppTheme, PodCapability.Share, PodCapability.ScheduledBackground, PodCapability.LocalNotification, PodCapability.SyncState, PodCapability.SyncMessage, PodCapability.SyncFile, PodCapability.Takeover],
  },
  "wearos-watch": {
    id: "wearos-watch",
    hostAbi: POD_HOST_ABI,
    logicalViewport: POD_LOGICAL_VIEWPORT,
    renderer: "vulkan-1.1",
    capabilities: [...common, PodCapability.Back, PodCapability.Network, PodCapability.Files, PodCapability.Sqlite, PodCapability.TextLayout, PodCapability.TextRaster, PodCapability.TextEnvironment, PodCapability.TextInput, PodCapability.TextInputState, PodCapability.TimeInput, PodCapability.Location, PodCapability.Battery, PodCapability.DeviceInfo, PodCapability.Secure, PodCapability.Crypto, PodCapability.FileChunks, PodCapability.Uri, PodCapability.Audio, PodCapability.DebugAcoustic, PodCapability.Tts, PodCapability.TtsSpectrum, PodCapability.Video, PodCapability.VideoSessions, PodCapability.Timer, PodCapability.DisplayPower, PodCapability.Toast, PodCapability.Images, PodCapability.BrowserChallenge, PodCapability.BrowserAuth, PodCapability.BrowserAuthStatus, PodCapability.HttpDownload, PodCapability.Rfcomm, PodCapability.Tcp, PodCapability.ByteStream, PodCapability.WebSocket, PodCapability.Discovery, PodCapability.BleProvision, PodCapability.Browser, PodCapability.BrowserSiteData, PodCapability.BrowserAvailability, PodCapability.BrowserInApp, PodCapability.BrowserInAppState, PodCapability.BrowserInAppTheme, PodCapability.Share, PodCapability.ScheduledBackground, PodCapability.LocalNotification, PodCapability.SyncState, PodCapability.SyncMessage, PodCapability.SyncFile, PodCapability.Takeover],
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
