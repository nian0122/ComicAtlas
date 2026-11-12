import { sha256 } from '@noble/hashes/sha2.js'
export async function hashBlob(blob: Blob, progress: (bytes: number) => void = () => {}, signal?: AbortSignal) {
  const hash = sha256.create()
  for (let offset = 0; offset < blob.size; offset += 4 * 1024 * 1024) {
    signal?.throwIfAborted()
    const part = await blob.slice(offset, offset + 4 * 1024 * 1024).arrayBuffer()
    signal?.throwIfAborted()
    hash.update(new Uint8Array(part))
    progress(Math.min(offset + part.byteLength, blob.size))
  }
  return Array.from(hash.digest(), (b) => b.toString(16).padStart(2, '0')).join('')
}
