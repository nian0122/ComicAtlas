import { ref, onBeforeUnmount } from 'vue'
import { systemApi, type ToolCapabilities, type SystemIdentity } from '@/entities/system'
import { getApiErrorMessage } from '@/shared/api/http'

export function useSystem() {
  const identity = ref<SystemIdentity | null>(null),
    capabilities = ref<ToolCapabilities | null>(null)
  const toolsLoading = ref(false),
    toolsFresh = ref(false),
    toolsError = ref(''),
    identityError = ref(''),
    observedAt = ref('')
  let disposed = false

  async function refresh() {
    if (toolsLoading.value) return
    toolsLoading.value = true
    toolsFresh.value = false
    toolsError.value = ''
    identityError.value = ''
    const [info, caps] = await Promise.allSettled([systemApi.identity(), systemApi.capabilities()])
    if (disposed) return
    if (info.status === 'fulfilled') identity.value = info.value
    else identityError.value = getApiErrorMessage(info.reason, '读取系统信息失败')
    if (caps.status === 'fulfilled') {
      capabilities.value = caps.value
      toolsFresh.value = true
      observedAt.value = new Date().toISOString()
    } else toolsError.value = getApiErrorMessage(caps.reason, '查询媒体工具失败')
    toolsLoading.value = false
  }
  onBeforeUnmount(() => {
    disposed = true
  })
  void refresh()
  return { identity, capabilities, toolsLoading, toolsFresh, toolsError, identityError, observedAt, refresh }
}
