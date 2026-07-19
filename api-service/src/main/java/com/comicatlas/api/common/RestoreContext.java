package com.comicatlas.api.common;

/**
 * 恢复上下文，封装一次恢复操作所需的所有参数。
 */
public record RestoreContext(Long comicId, boolean comicExists, RestorePolicy policy, RestoreSource source) {
}
