<template>
  <router-view />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'

let frameId: number | null = null

function syncViewportHeight(): void {
  if (frameId !== null) return
  frameId = window.requestAnimationFrame(() => {
    frameId = null
    const viewportHeight = window.visualViewport?.height ?? window.innerHeight
    document.documentElement.style.setProperty('--app-viewport-height', `${viewportHeight}px`)
  })
}

onMounted(() => {
  const visualViewport = window.visualViewport
  window.addEventListener('resize', syncViewportHeight, { passive: true })
  window.addEventListener('orientationchange', syncViewportHeight, { passive: true })
  visualViewport?.addEventListener('resize', syncViewportHeight, { passive: true })
  visualViewport?.addEventListener('scroll', syncViewportHeight, { passive: true })
  syncViewportHeight()
})

onBeforeUnmount(() => {
  const visualViewport = window.visualViewport
  window.removeEventListener('resize', syncViewportHeight)
  window.removeEventListener('orientationchange', syncViewportHeight)
  visualViewport?.removeEventListener('resize', syncViewportHeight)
  visualViewport?.removeEventListener('scroll', syncViewportHeight)
  if (frameId !== null) window.cancelAnimationFrame(frameId)
})
</script>
