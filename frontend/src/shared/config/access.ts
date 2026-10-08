/** 由公网入口注入，仅控制界面；权限边界由远端代理白名单执行。 */
export const isPublicReading =
  typeof document !== 'undefined' &&
  document.querySelector('meta[name="comicatlas-access"]')?.getAttribute('content') === 'public-reading'
