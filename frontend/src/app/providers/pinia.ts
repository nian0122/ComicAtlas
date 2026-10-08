import type { App } from 'vue'
import { createPinia } from 'pinia'

export function installState(application: App): void {
  application.use(createPinia())
}
