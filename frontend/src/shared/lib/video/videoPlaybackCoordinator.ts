// ============================================================================
// VideoPlaybackCoordinator — media-session-based singleton
//
// Key design:
// - mediaId (number) is the authoritative session key, not DOM node identity.
// - Only ONE session active at a time. Activating a new session pauses and
//   saves the previous one synchronously — no async gap.
// - Position registry is in-memory only (Map<number, number>), scoped to the
//   current Reader component session. No localStorage, no Pinia, no API calls.
// ============================================================================

// ---------------------------------------------------------------------------
// Active session
// ---------------------------------------------------------------------------

interface ActiveSession {
  video: HTMLVideoElement
  mediaId: number
}

let activeSession: ActiveSession | null = null

// ---------------------------------------------------------------------------
// In-memory position registry
// ---------------------------------------------------------------------------

const positionMap = new Map<number, number>()

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/** Pause the current active session's video, save its currentTime, and clear the slot. */
function pauseAndSaveActive(): void {
  if (activeSession === null) return
  const { video, mediaId } = activeSession
  if (!video.paused) {
    video.pause()
  }
  savePosition(mediaId, video.currentTime)
  activeSession = null
}

// ---------------------------------------------------------------------------
// Public API (new — mediaId-based)
// ---------------------------------------------------------------------------

/**
 * Activate a video session for the given mediaId.
 *
 * - If a different mediaId is currently active: pause its video, save its position,
 *   clear the slot, then activate the new session.
 * - If the same mediaId is already active: update the video reference (no pause).
 * - If nothing is active: activate directly.
 *
 * Returns `true` when this mediaId is now the active session (always true for a
 * valid call — callers can use `isActive()` for defensive checks after async ops).
 */
export function activateSession(mediaId: number, videoEl: HTMLVideoElement): boolean {
  if (activeSession !== null && activeSession.mediaId !== mediaId) {
    pauseAndSaveActive()
  }
  activeSession = { video: videoEl, mediaId }
  return true
}

/**
 * Release the session for `mediaId`.
 *
 * - If this mediaId matches the active session: save its position and clear the slot.
 * - If it does NOT match: do nothing (prevents a stale release from harming the
 *   current session).
 */
export function releaseSession(mediaId: number): void {
  if (activeSession !== null && activeSession.mediaId === mediaId) {
    savePosition(mediaId, activeSession.video.currentTime)
    activeSession = null
  }
}

/**
 * Save a manual playback position for a mediaId.
 *
 * Guards against NaN and negative values.
 */
export function savePosition(mediaId: number, currentTime: number): void {
  if (isNaN(currentTime) || currentTime < 0) return
  positionMap.set(mediaId, currentTime)
}

/**
 * Retrieve the saved playback position for a mediaId, or 0 if never saved.
 *
 * Always returns 0 for unknown mediaIds — never stale data.
 */
export function getPosition(mediaId: number): number {
  return positionMap.get(mediaId) ?? 0
}

/**
 * Pause the currently active video (if any) and save its position.
 *
 * Safe to call when nothing is active (no-op).
 */
export function pauseCurrent(): void {
  pauseAndSaveActive()
}

/**
 * Return whether the given mediaId currently holds the active session lock.
 *
 * Useful for race-condition guards: after an async `play()` resolves, check
 * `isActive(mediaId)` before committing any side effects.
 */
export function isActive(mediaId: number): boolean {
  return activeSession !== null && activeSession.mediaId === mediaId
}

// ============================================================================
// Legacy API — preserved for backward compatibility with VideoPlayer.vue.
// These operate on DOM-node identity and are independent of the new API.
// Will be removed once VideoPlayer.vue migrates to activateSession/releaseSession.
// ============================================================================

let activeVideo: HTMLVideoElement | null = null

/** @deprecated Use activateSession(mediaId, videoEl) instead. */
export function activateVideo(video: HTMLVideoElement): void {
  if (activeVideo !== null && activeVideo !== video) {
    activeVideo.pause()
  }
  activeVideo = video
}

/** @deprecated Use releaseSession(mediaId) instead. */
export function releaseVideo(video: HTMLVideoElement): void {
  if (activeVideo === video) {
    activeVideo = null
  }
}
