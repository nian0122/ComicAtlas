import { StorageOperationType } from '@/entities/storage'
import type { StorageOperationType as StorageOperationTypeValue } from '@/entities/storage'
import { useStorageStore } from '@/features/storage/store'
import { onScopeDispose } from 'vue'

interface PollEntry {
  timer: ReturnType<typeof setTimeout> | null
  type: StorageOperationTypeValue
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
      clearTimeout(entry.timer)
    }
    store.setBusy(comicId, false)
    activePolls.delete(comicId)
  }

  function start(comicId: number, type: StorageOperationTypeValue) {
    stop(comicId)
    store.setBusy(comicId, true)

    const entry: PollEntry = { timer: null, type, retries: 0 }
    activePolls.set(comicId, entry)

    const poll = async () => {
      await store.refreshRow(comicId)
      if (activePolls.get(comicId) !== entry) return
      entry.retries++

      const comic = store.comicList.find((c) => c.comicId === comicId)
      let shouldStop = false

      if (type === StorageOperationType.DeleteHQ) {
        if (comic && (comic.hqStatus === 'DELETED' || comic.hqStatus === 'EMPTY')) {
          shouldStop = true
        }
      } else if (type === StorageOperationType.GenerateLQ) {
        if (comic && comic.lqStatus === 'READY') {
          shouldStop = true
        }
      } else if (type === StorageOperationType.TranscodeVideos) {
        if (
          comic &&
          (comic.transcodeStatus === 'DONE' ||
            comic.transcodeStatus === 'NOT_NEEDED' ||
            comic.transcodeStatus === 'FAILED')
        ) {
          shouldStop = true
        }
      }

      if (entry.retries >= MAX_RETRIES) {
        shouldStop = true
      }

      if (shouldStop) {
        stop(comicId)
      } else {
        entry.timer = setTimeout(poll, POLL_INTERVAL)
      }
    }
    entry.timer = setTimeout(poll, POLL_INTERVAL)
  }

  function stopAll() {
    for (const comicId of Array.from(activePolls.keys())) {
      stop(comicId)
    }
  }

  onScopeDispose(stopAll)
  return { start, stop, stopAll }
}
