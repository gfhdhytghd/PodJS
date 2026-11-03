export const p256: {
  utils: {isValidPrivateKey(key: Uint8Array): boolean};
  getPublicKey(key: Uint8Array, compressed: boolean): Uint8Array;
  getSharedSecret(key: Uint8Array, point: Uint8Array, compressed: boolean): Uint8Array;
  ProjectivePoint: {fromHex(point: Uint8Array): {assertValidity(): void}};
  sign(digest: Uint8Array, key: Uint8Array, options: {lowS: boolean; extraEntropy: Uint8Array}): {toCompactRawBytes(): Uint8Array};
};
