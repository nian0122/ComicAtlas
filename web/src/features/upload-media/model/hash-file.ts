export function hashFile(file: Blob, progress: (bytes: number) => void, signal: AbortSignal): Promise<string> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./sha256.worker.ts', import.meta.url), { type: 'module' })
    function finish() {
      worker.terminate()
      signal.removeEventListener('abort', abort)
    }
    function abort() {
      finish()
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
    worker.onmessage = (event: MessageEvent<{ bytes?: number; digest?: string; error?: string }>) => {
      if (event.data.bytes !== undefined) progress(event.data.bytes)
      if (event.data.error) {
        finish()
        reject(new Error(event.data.error))
      }
      if (event.data.digest) {
        finish()
        resolve(event.data.digest)
      }
    }
    worker.onerror = () => {
      finish()
      reject(new Error('摘要计算失败，请重新选择文件后重试'))
    }
    worker.postMessage(file)
  })
}
