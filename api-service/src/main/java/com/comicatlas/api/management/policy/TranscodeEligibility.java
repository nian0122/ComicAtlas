package com.comicatlas.api.management.policy;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TranscodeStatus;

/**
 * 视频手动转码资格统一判定（全项目唯一入口）。
 * <p>
 * <b>为什么只用这一处：</b>导入把转码需求表达为 {@code transcodeStatus}（兼容 →
 * {@link TranscodeStatus#NOT_NEEDED}，不兼容/未知 → {@link TranscodeStatus#REQUIRED}），
 * 因此资格判定不再看容器字符串（容器兼容性已由状态表达），只看媒体当前 DB 状态。
 * <p>
 * <b>规则：</b>{@code VIDEO} 类型 + HQ 可用（非 DELETED）+ 生命周期活动（READY）
 * + {@code transcodeStatus ∈ {REQUIRED, FAILED}}。FAILED 表示转码失败可重试。
 * <p>
 * 纯静态无状态工具，不依赖 Spring、不做任何 I/O。
 */
public final class TranscodeEligibility {

    private TranscodeEligibility() {}

    /**
     * 判定媒体当前是否允许手动发起视频转码。
     *
     * @param media 媒体行（可能为 null）
     * @return true 表示可发起转码
     */
    public static boolean isEligible(Media media) {
        if (media == null || !"VIDEO".equals(media.getMediaType())) {
            return false;
        }
        if (media.getHqStatus() == HqStatus.DELETED) {
            return false;
        }
        if (media.getStatus() != MediaLifecycleStatus.READY) {
            return false;
        }
        TranscodeStatus status = media.getTranscodeStatus();
        return status == TranscodeStatus.REQUIRED || status == TranscodeStatus.FAILED;
    }
}
