import { StorageOperationType } from '@/types'
import { useStorageStore } from '@/stores/management/storage'

interface PollEntry {
  timer: ReturnType<typeof setInterval> | null
  type: StorageOperationType
  retries: number
}

const POLL_INTERVAL = 5000
const MAX_RETRIES = 12

export function useStoragePolling(store: ReturnType<typeof useStorageStore>) {
  const activePolls = new Map<number, PollEntry>()

  function stop(comicId: number) {
    const entry = activePolls.get(comicId)
    if (!entry) return

    if (entry.timer !== null) {
      clearInterval(entry.timer)
    }
    store.setBusy(comicId, false)
    activePolls.delete(comicId)
  }

  function start(comicId: number, type: StorageOperationType) {
    store.setBusy(comicId, true)

    const entry: PollEntry = { timer: null, type, retries: 0 }
    activePolls.set(comicId, entry)

    entry.timer = setInterval(async () => {
      await store.refreshRow(comicId)
      entry.retries++

      const comic = store.comicList.find((c) => c.comicId === comicId)
      let shouldStop = false

      if (type === StorageOperationType.DeleteHQ) {
        if (comic && (comic.hqStatus === 'DELETED' || comic.hqStatus === 'EMPTY')) {
          shouldStop = true
        }
      } else if (type === StorageOperationType.GenerateLQ) {
        // LQ 仅 QUEUED/GENERATING 持续轮询，其余（READY/FAILED/NOT_GENERATED 等）停止
        if (comic && comic.lqStatus !== 'QUEUED' && comic.lqStatus !== 'GENERATING') {
          shouldStop = true
        }
      } else if (type === StorageOperationType.TranscodeVideos) {
        // 转码仅 QUEUED/TRANSCODING 持续轮询，其余（REQUIRED/FAILED/READY/NOT_NEEDED）停止
        if (comic && comic.transcodeStatus !== 'QUEUED' && comic.transcodeStatus !== 'TRANSCODING') {
          shouldStop = true
        }
      }

      if (entry.retries >= MAX_RETRIES) {
        shouldStop = true
      }

      if (shouldStop) {
        stop(comicId)
      }
    }, POLL_INTERVAL)
  }

  function stopAll() {
    for (const comicId of Array.from(activePolls.keys())) {
      stop(comicId)
    }
  }

  return { start, stop, stopAll }
}
