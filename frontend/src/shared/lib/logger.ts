type LogContext = Readonly<Record<string, string | number | boolean | undefined>>

function emitProductionError(message: string, context: LogContext): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(
    new CustomEvent('comicatlas:client-error', {
      detail: { message, context },
    }),
  )
}

/** 前端最小日志门面：生产环境不向控制台输出，也不记录请求载荷或本地路径。 */
export const clientLogger = {
  debug(message: string, context: LogContext = {}): void {
    if (import.meta.env.DEV) console.debug(`[ComicAtlas] ${message}`, context)
  },
  error(message: string, context: LogContext = {}): void {
    if (import.meta.env.DEV) {
      console.error(`[ComicAtlas] ${message}`, context)
    } else {
      emitProductionError(message, context)
    }
  },
}
