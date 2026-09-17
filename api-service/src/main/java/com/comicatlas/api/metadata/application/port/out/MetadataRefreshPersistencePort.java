package com.comicatlas.api.metadata.application.port.out;

import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;

import java.math.BigDecimal;

import java.util.List;

/** 元数据刷新差异合并所需的持久化输出端口。 */
public interface MetadataRefreshPersistencePort {
    List<ChapterSnapshot> findChapters(Long comicId);

    List<MediaSnapshot> findActiveMedia(List<Long> chapterIds, List<String> inactiveStatuses);

    int normalizeLegacyHqPath(Long chapterId, String oldPrefix, String newPrefix);

    int normalizeLegacyLqPath(Long chapterId, String oldPrefix, String newPrefix);

    void updateMediaRefreshBatch(List<MediaUpdateCommand> media);

    void updateChapterPageCountBatch(List<ChapterPageCountCommand> chapters);

    record ChapterSnapshot(Long id, Integer version) {
    }

    record MediaSnapshot(Long id, Long chapterId, String hqPath, HqStatus hqStatus, Long hqSize,
                         Integer width, Integer height, String mediaType, BigDecimal duration,
                         String container, String videoCodec, String audioCodec, LqStatus lqStatus,
                         String lqRoot, String lqPath, Long lqSize, MediaLifecycleStatus status, Integer version) {
    }

    record MediaUpdateCommand(Long id, Long chapterId, String hqPath, HqStatus hqStatus, Long hqSize,
                              Integer width, Integer height, String mediaType, BigDecimal duration,
                              String container, String videoCodec, String audioCodec, LqStatus lqStatus,
                              String lqRoot, String lqPath, Long lqSize, Integer version) {
    }

    record ChapterPageCountCommand(Long id, Integer pageCount, Integer version) {
    }
}
