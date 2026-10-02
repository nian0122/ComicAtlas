import { onScopeDispose } from 'vue'

/** 串行轮询；停止或销毁作用域后，在途请求不能再次创建定时器。 */
export function useAsyncPolling(refresh: () => Promise<boolean>, interval: number) {
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
    const currentGeneration = generation
    const schedule = () => {
      if (disposed || currentGeneration !== generation) return
      timer = setTimeout(async () => {
        timer = undefined
        if (disposed || currentGeneration !== generation) return
        if (await refresh()) schedule()
      }, interval)
    }
    schedule()
  }

  onScopeDispose(() => {
    disposed = true
    stop()
  })

  return { start, stop }
}
