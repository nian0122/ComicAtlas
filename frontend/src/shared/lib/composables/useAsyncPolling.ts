import { onScopeDispose, readonly, shallowRef } from 'vue'

/** 串行轮询；停止或销毁作用域后，在途请求不能再次创建定时器。 */
export function useAsyncPolling(refresh: () => Promise<boolean>, interval: number) {
  const error = shallowRef<unknown>(null)
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let disposed = false

  function stop(): void {
    generation += 1
    clearTimeout(timer)
    timer = undefined
  }

  function start(): void {
    stop()
    if (disposed) return
    error.value = null
    const currentGeneration = generation
    const schedule = () => {
      if (disposed || currentGeneration !== generation) return
      timer = setTimeout(async () => {
        timer = undefined
        if (disposed || currentGeneration !== generation) return
        try {
          if (await refresh()) schedule()
        } catch (failure) {
          if (disposed || currentGeneration !== generation) return
          error.value = failure
          stop()
        }
      }, interval)
    }
    schedule()
  }

  onScopeDispose(() => {
    disposed = true
    stop()
  })

  return { start, stop, error: readonly(error) }
}
