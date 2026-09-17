import type { App } from 'vue'
import VueVirtualScroller from 'vue-virtual-scroller'

export function installAppPlugins(application: App): void {
  application.use(VueVirtualScroller)
}
