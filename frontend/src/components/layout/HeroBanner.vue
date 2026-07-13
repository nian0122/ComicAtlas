<template>
  <section class="hero-banner">
    <div
      class="hero-background"
      :style="{ backgroundImage: `url(${backgroundUrl})` }"
      aria-hidden="true"
    >
      <div class="hero-overlay" />
    </div>

    <div class="hero-content">
      <div v-if="posterUrl" class="hero-poster">
        <img :src="posterUrl" :alt="title" loading="eager" />
      </div>

      <div class="hero-info">
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
              {{ primaryAction.label }}
            </button>
            <button
              v-if="secondaryAction"
              type="button"
              class="hero-btn hero-btn--secondary"
              @click="secondaryAction.onClick"
            >
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
  width: 100%;
  min-height: 70vh;
  display: flex;
  align-items: center;
  overflow: hidden;
  color: var(--text-primary);
}

.hero-background {
  position: absolute;
  inset: 0;
  left: 50%;
  width: 100vw;
  transform: translateX(-50%);
  background-size: cover;
  background-position: center top;
  background-repeat: no-repeat;
  filter: blur(24px) brightness(0.6);
  z-index: 0;
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
  width: 100%;
  max-width: var(--page-width);
  margin: 0 auto;
  padding: calc(var(--nav-height) + var(--space-2xl)) var(--page-padding)
    var(--space-3xl);
  display: flex;
  align-items: flex-end;
  gap: var(--space-2xl);
}

.hero-poster {
  flex-shrink: 0;
  width: min(320px, 30vw);
  aspect-ratio: 2 / 3;
  border-radius: var(--card-radius);
  overflow: hidden;
  box-shadow: var(--card-shadow);
}

.hero-poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hero-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-base);
  max-width: min(720px, 55%);
}

.hero-title {
  margin: 0;
  font-family: var(--heading);
  font-size: 48px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}

.hero-subtitle {
  margin: 0;
  font-size: 18px;
  font-weight: 500;
  color: var(--text-secondary);
}

.hero-description {
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-base);
  margin-top: var(--space-base);
}

.hero-btn {
  padding: 8px 20px;
  border: none;
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: transform var(--transition-fast),
    background-color var(--transition-fast);
}

.hero-btn--primary {
  background: var(--accent);
  color: var(--text-primary);
}

.hero-btn--secondary {
  background: rgba(255, 255, 255, 0.2);
  color: var(--text-primary);
}

.hero-btn--secondary:hover {
  background: rgba(255, 255, 255, 0.3);
}

@media (max-width: 768px) {
  .hero-banner {
    min-height: 50vh;
  }

  .hero-content {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-lg);
  }

  .hero-poster {
    width: min(200px, 45vw);
  }

  .hero-info {
    max-width: 100%;
  }

  .hero-title {
    font-size: 32px;
  }
}
</style>
