package com.comicatlas.api.media.application.port.out;

import java.util.List;

/** HQ 媒体登记持久化输出端口。 */
public interface HqMediaRegistrationPersistencePort {
    List<ChapterSnapshot> findChapters(Long comicId);

    List<MediaSnapshot> findMediaByChapters(List<Long> chapterIds);

    void insertMediaBatch(List<MediaRegistrationCommand> media);

    record ChapterSnapshot(Long id, Integer version) {
    }

    record MediaSnapshot(Long id, Long chapterId, Integer pageNumber, String hqPath,
                        String status, Integer version) {
    }

    record MediaRegistrationCommand(Long chapterId, Integer pageNumber, String hqRoot, String hqPath,
                                    String hqStatus, Long hqSize, String lqStatus, Long lqSize,
                                    String transcodeStatus, String status, String mediaType,
                                    Integer width, Integer height, java.math.BigDecimal duration,
                                    String container, String videoCodec, String audioCodec) {
    }
}
