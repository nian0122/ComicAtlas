<template>
  <section :class="['hero-banner', `hero-banner--${variant}`]">
    <div
      :class="['hero-background', { 'hero-background--empty': !backgroundUrl }]"
      :style="backgroundUrl ? { backgroundImage: `url(${backgroundUrl})` } : undefined"
      aria-hidden="true"
    >
      <div class="hero-overlay" />
    </div>

    <div class="hero-content">
      <div class="hero-poster">
        <div v-if="posterUrl" class="hero-poster-bg" :style="{ backgroundImage: `url(${posterUrl})` }" />
        <div v-else class="hero-poster-placeholder">
          <el-icon :size="64"><VideoPlay /></el-icon>
        </div>
      </div>

      <div class="hero-info">
        <p class="hero-kicker">
          <span class="hero-kicker-dot" aria-hidden="true" />
          {{ kicker || (backgroundUrl ? 'CONTINUE YOUR SCREENING' : 'PRIVATE COMIC ARCHIVE') }}
        </p>
        <h1 class="hero-title" :title="titleTooltip || title">{{ title }}</h1>
        <p v-if="subtitle" class="hero-subtitle">{{ subtitle }}</p>

        <div v-if="hasDescription" class="hero-description">
          <slot name="description">{{ description }}</slot>
        </div>

        <div v-if="hasActions" class="hero-actions">
          <slot name="actions">
            <AppButton
              v-if="primaryAction"
              variant="primary"
              type="button"
              class="hero-action hero-action--primary"
              @click="primaryAction.onClick"
            >
              <el-icon :size="16"><VideoPlay /></el-icon>
              {{ primaryAction.label }}
            </AppButton>
            <AppButton
              v-if="secondaryAction"
              variant="overlay"
              type="button"
              class="hero-action hero-action--secondary"
              @click="secondaryAction.onClick"
            >
              <el-icon :size="16">
                <VideoPlay v-if="secondaryAction.icon === 'play'" />
                <InfoFilled v-else />
              </el-icon>
              {{ secondaryAction.label }}
            </AppButton>
          </slot>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { AppButton } from '@/shared/ui/button'
import { computed, useSlots } from 'vue'
import { InfoFilled, VideoPlay } from '@element-plus/icons-vue'

interface HeroAction {
  label: string
  onClick: () => void
  icon?: 'info' | 'play'
}

interface HeroBannerProps {
  backgroundUrl: string
  posterUrl?: string
  variant?: 'default' | 'detail'
  kicker?: string
  title: string
  titleTooltip?: string
  subtitle?: string
  description?: string
  primaryAction?: HeroAction
  secondaryAction?: HeroAction
}

const props = defineProps<HeroBannerProps>()

const variant = computed(() => props.variant || 'default')

defineSlots<{
  description?: () => unknown
  actions?: () => unknown
}>()

const slots = useSlots()

const hasDescription = computed(() => Boolean(slots.description) || Boolean(props.description))
const hasActions = computed(
  () => Boolean(slots.actions) || Boolean(props.primaryAction) || Boolean(props.secondaryAction),
)
</script>

<style scoped>
.hero-banner {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  min-height: var(--home-hero-height);
  overflow: hidden;
  color: var(--text-primary);
}

/* 详情页专属沉浸式 Hero：首页 Hero 保持原有节奏，避免样式相互影响。 */
.hero-banner--detail {
  --detail-hero-copy-width: min(100%, 48rem);
  min-height: clamp(420px, 54vh, 620px);
  background: var(--bg-primary);
}

.hero-banner--detail .hero-background {
  background-position: center 24%;
  filter: saturate(0.88) brightness(0.62) contrast(1.02);
  transform: translateX(-50%) scale(1.07);
}

.hero-banner--detail .hero-overlay {
  background:
    linear-gradient(90deg, rgb(0 0 0 / 76%) 0%, rgb(0 0 0 / 42%) 56%, rgb(0 0 0 / 10%) 100%),
    linear-gradient(0deg, rgb(8 8 8 / 72%) 0%, rgb(0 0 0 / 48%) 20%, transparent 66%);
}

.hero-banner--detail .hero-content {
  display: flex;
  align-items: flex-end;
  min-height: inherit;
  box-sizing: border-box;
  padding-top: clamp(180px, 26vh, 280px);
  padding-bottom: clamp(var(--space-8), 9vh, var(--space-16));
}

.hero-banner--detail .hero-poster {
  display: none;
}

.hero-banner--detail .hero-info {
  max-width: min(820px, 76vw);
}

.hero-banner--detail .hero-kicker {
  color: rgb(255 255 255 / 68%);
}

.hero-banner--detail .hero-title {
  width: var(--detail-hero-copy-width);
  max-width: var(--detail-hero-copy-width);
  font-size: clamp(2.4rem, 5vw, 4.8rem);
  text-wrap: balance;
  overflow-wrap: break-word;
}

.hero-banner--detail .hero-subtitle {
  color: rgb(255 255 255 / 78%);
}

.hero-banner--detail .progress-block {
  padding: var(--space-4) var(--space-5);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: var(--radius-sm);
  background: rgb(0 0 0 / 22%);
  backdrop-filter: blur(8px);
}

.hero-background {
  position: absolute;
  inset: 0;
  left: 50%;
  width: 100vw;
  background-repeat: no-repeat;
  background-position: 70% 20%;
  background-size: cover;
  filter: saturate(0.78) brightness(0.38);
  transform: translateX(-50%) scale(1.02);
  z-index: 0;
}

/* 首页封面应当参与视觉叙事，只在文字列保留必要的暗角。 */
.hero-banner--default .hero-background {
  filter: saturate(0.9) brightness(0.62) contrast(1.02);
}

.hero-banner--default .hero-overlay {
  background:
    linear-gradient(to right, rgb(6 6 6 / 80%) 0%, rgb(6 6 6 / 48%) 46%, rgb(6 6 6 / 12%) 100%),
    linear-gradient(to top, rgb(8 8 8 / 70%) 0%, transparent 56%),
    linear-gradient(to bottom, rgb(8 8 8 / 14%) 0%, transparent 32%);
}

.hero-background--empty {
  background-image:
    radial-gradient(circle at 74% 42%, var(--accent-bg), transparent 26rem),
    linear-gradient(120deg, var(--bg-primary), var(--bg-secondary));
  filter: none;
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background: var(--hero-gradient);
  z-index: 1;
}

.hero-content {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: var(--home-poster-width) minmax(0, 1fr);
  align-items: center;
  gap: var(--home-hero-content-gap);
  width: 100%;
  max-width: var(--page-width);
  margin: 0 auto;
  padding: calc(var(--nav-height) + var(--space-8)) var(--page-padding) var(--space-8);
}

.hero-poster {
  width: 100%;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border-radius: var(--radius-xs);
  background: var(--bg-surface);
  box-shadow: var(--shadow-mount);
}

.hero-poster-bg {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
}

.hero-poster-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  background: var(--bg-surface);
}

.hero-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
  max-width: 720px;
  padding-bottom: var(--space-2);
}

.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0 0 var(--space-1);
  color: var(--text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
  letter-spacing: var(--tracking-kicker);
}

.hero-kicker-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-bg);
}

.hero-title {
  margin: 0;
  font-family: var(--heading);
  color: var(--text-primary);
  font-size: var(--home-hero-title-size);
  font-weight: 800;
  letter-spacing: -0.045em;
  line-height: 1.02;
  text-shadow: var(--title-shadow);
}

.hero-subtitle {
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--space-4);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--text-secondary);
  text-wrap: pretty;
}

.hero-description {
  display: -webkit-box;
  max-width: 64ch;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: 1.6;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-base);
  margin-top: var(--space-base);
}

/* 标题、进度与操作共享同一阅读列，避免进度条显得像孤立的短横线。 */
.hero-banner--detail .hero-description {
  width: var(--detail-hero-copy-width);
  max-width: var(--detail-hero-copy-width);
}

/* Hero 操作保持电影海报般的克制：清楚的主操作与安静的辅助操作，不再做卡片化装饰。 */
.hero-actions :deep(.hero-action) {
  min-width: 132px;
  min-height: 44px;
  padding-inline: var(--space-4);
  border-radius: 6px;
  font-size: var(--text-sm);
  font-weight: 700;
  letter-spacing: 0.01em;
}

.hero-actions :deep(.hero-action .app-button__content) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: 100%;
  line-height: 1;
}

.hero-actions :deep(.hero-action .el-icon) {
  display: inline-flex;
  flex: 0 0 16px;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  line-height: 1;
}

.hero-actions :deep(.hero-action .el-icon svg) {
  display: block;
}

.hero-actions :deep(.hero-action--primary) {
  box-shadow: 0 8px 20px rgb(229 9 20 / 22%);
}

.hero-actions :deep(.hero-action--primary:hover:not(:disabled)) {
  transform: translateY(-1px);
}

.hero-actions :deep(.hero-action--secondary) {
  color: rgb(255 255 255 / 90%);
  background: rgb(5 5 5 / 38%);
  border-color: rgb(255 255 255 / 30%);
}

.hero-actions :deep(.hero-action--secondary:hover:not(:disabled)) {
  background: rgb(255 255 255 / 12%);
  border-color: rgb(255 255 255 / 54%);
  transform: translateY(-1px);
}

@media (max-width: 1024px) {
  .hero-banner {
    min-height: 0;
    aspect-ratio: 4 / 5;
    align-items: end;
  }

  .hero-background {
    background-position: center 16%;
    filter: saturate(0.94) brightness(0.68);
  }

  .hero-banner--detail .hero-background {
    filter: saturate(0.92) brightness(0.7);
  }

  .hero-poster {
    display: none;
  }

  .hero-overlay {
    background: var(--hero-mobile-gradient);
  }

  .hero-content {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: flex-end;
    padding: 0 var(--mobile-page-gutter) var(--space-8);
  }

  .hero-info {
    max-width: 100%;
    gap: var(--space-3);
    padding: 0;
  }

  .hero-kicker {
    font-size: 10px;
  }

  .hero-title {
    max-width: 100%;
    font-size: clamp(1.75rem, 8vw, 2.5rem);
    letter-spacing: -0.04em;
  }

  .hero-subtitle {
    font-size: var(--text-sm);
  }

  .hero-description {
    -webkit-line-clamp: 2;
  }

  .hero-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
    margin-top: var(--space-3);
  }

  .hero-actions:has(.hero-action:only-child) {
    grid-template-columns: minmax(172px, max-content);
  }
}

@media (min-width: 600px) and (max-width: 1024px) {
  .hero-banner {
    aspect-ratio: 16 / 10;
    max-height: var(--tablet-hero-max-height);
  }

  /* 平板断点同样显示底部导航，Hero 的最后一个操作不能被它遮住。 */
  .hero-banner--detail .hero-content {
    padding-bottom: calc(var(--mobile-tabbar-height) + var(--space-8));
  }
}
</style>
