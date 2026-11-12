import { hashBlob } from './sha256-stream'
self.onmessage = async (event: MessageEvent<Blob>) => {
  try {
    const digest = await hashBlob(event.data, (bytes) => self.postMessage({ bytes }))
    self.postMessage({ digest })
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : '文件读取失败' })
  }
}
