let activeVideo: HTMLVideoElement | null = null

/** 激活一个视频，并暂停此前仍在播放的实例。 */
export function activateVideo(video: HTMLVideoElement): void {
  if (activeVideo !== null && activeVideo !== video) {
    activeVideo.pause()
  }
  activeVideo = video
}

/** 在视频暂停、结束或销毁时释放全局播放占用。 */
export function releaseVideo(video: HTMLVideoElement): void {
  if (activeVideo === video) {
    activeVideo = null
  }
}
