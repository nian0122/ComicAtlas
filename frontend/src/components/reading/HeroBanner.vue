<template>
  <section class="hero-banner">
    <div
      :class="['hero-background', { 'hero-background--empty': !backgroundUrl }]"
      :style="backgroundUrl ? { backgroundImage: `url(${backgroundUrl})` } : undefined"
      aria-hidden="true"
    >
      <div class="hero-overlay" />
    </div>

    <div class="hero-content">
      <div class="hero-poster">
        <div
          v-if="posterUrl"
          class="hero-poster-bg"
          :style="{ backgroundImage: `url(${posterUrl})` }"
        />
        <div v-else class="hero-poster-placeholder">
          <el-icon :size="64"><VideoPlay /></el-icon>
        </div>
      </div>

      <div class="hero-info">
        <p class="hero-kicker">
          <span class="hero-kicker-dot" aria-hidden="true" />
          {{ backgroundUrl ? 'CONTINUE YOUR SCREENING' : 'PRIVATE COMIC ARCHIVE' }}
        </p>
        <h1 class="hero-title">{{ title }}</h1>
        <p v-if="subtitle" class="hero-subtitle">{{ subtitle }}</p>

        <div v-if="hasDescription" class="hero-description">
          <slot name="description">{{ description }}</slot>
        </div>

        <div v-if="hasActions" class="hero-actions">
          <slot name="actions">
            <button
              v-if="primaryAction"
              type="button"
              class="hero-btn hero-btn--primary btn-hover"
              @click="primaryAction.onClick"
            >
              <el-icon :size="20"><VideoPlay /></el-icon>
              {{ primaryAction.label }}
            </button>
            <button
              v-if="secondaryAction"
              type="button"
              class="hero-btn hero-btn--secondary"
              @click="secondaryAction.onClick"
            >
              <el-icon :size="20"><InfoFilled /></el-icon>
              {{ secondaryAction.label }}
            </button>
          </slot>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, useSlots } from 'vue'
import { InfoFilled, VideoPlay } from '@element-plus/icons-vue'

interface HeroAction {
  label: string
  onClick: () => void
}

interface HeroBannerProps {
  backgroundUrl: string
  posterUrl?: string
  title: string
  subtitle?: string
  description?: string
  primaryAction?: HeroAction
  secondaryAction?: HeroAction
}

const props = defineProps<HeroBannerProps>()

defineSlots<{
  description?: () => unknown
  actions?: () => unknown
}>()

const slots = useSlots()

const hasDescription = computed(
  () => Boolean(slots.description) || Boolean(props.description)
)
const hasActions = computed(
  () =>
    Boolean(slots.actions) ||
    Boolean(props.primaryAction) ||
    Boolean(props.secondaryAction)
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

.hero-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: var(--control-min-size);
  padding: 0 var(--space-5);
  border: none;
  border-radius: var(--radius-xs);
  font-family: inherit;
  font-size: var(--text-sm);
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  transition: transform var(--transition-fast),
    background-color var(--transition-fast);
}

.hero-btn--primary {
  background: var(--accent);
  color: var(--color-on-brand);
}

.hero-btn--primary:hover {
  background: var(--accent-hover);
  transform: translateY(-2px);
}

.hero-btn--secondary {
  border: 1px solid var(--accent-border);
  background: var(--accent-bg);
  color: var(--text-primary);
}

.hero-btn--secondary:hover {
  background: var(--color-overlay-hover);
  transform: translateY(-2px);
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
    max-width: 18ch;
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

  .hero-btn {
    width: 100%;
    padding-inline: var(--space-3);
  }
}

@media (min-width: 600px) and (max-width: 1024px) {
  .hero-banner {
    aspect-ratio: 16 / 10;
    max-height: var(--tablet-hero-max-height);
  }
}
</style>
