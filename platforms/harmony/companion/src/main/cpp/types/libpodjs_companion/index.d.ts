export const companionStateRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionStateCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export const companionOutboxRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionOutboxCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export const companionInboxRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionPairingsRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionPairingsCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export const companionInboxCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export const companionFileRequestsRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionOutgoingTransfersRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionOutgoingTransfersCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export interface IncomingFileManifest {
  transfer_id: string;
  size: number;
  sha256: string;
  mime: string;
  chunk_hashes: string[];
}
export interface IncomingFileRequest {
  method: string;
  text: string;
  peer: string;
  manifest: IncomingFileManifest;
  index: number;
  data: Uint8Array;
}
export const incomingFilesOpen: (filesDir: string, appId: string) => Promise<Object>;
export const companionPairingLeaseOpen: (filesDir: string, appId: string) => Promise<Object>;
export const companionPairingLeaseRead: (lease: Object) => Promise<string | null>;
export const companionPairingLeaseCompareExchange: (lease: Object, expected: string | null, desired: string) => Promise<boolean>;
export const companionPairingLeaseAssert: (lease: Object) => void;
export const companionPairingLeaseClose: (lease: Object) => void;
export const outgoingFilesOpen: (filesDir: string, appId: string) => Promise<Object>;
export const guestFileOpen: (filesDir: string, path: string) => Promise<Object>;
export const guestFileSize: (lease: Object) => number;
export const incomingFilesClose: (lease: Object) => void;
export const incomingFilesRun: (lease: Object, request: IncomingFileRequest) => Promise<string | null | number[] | Uint8Array | void>;
export const companionFileRequestsCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
