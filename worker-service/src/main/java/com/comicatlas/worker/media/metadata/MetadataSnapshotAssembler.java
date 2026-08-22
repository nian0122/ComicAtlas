package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 负责将扫描结果组装为元数据刷新快照 DTO。 */
@Component
@RequiredArgsConstructor
public class MetadataSnapshotAssembler {

    public ChapterSnapshot chapterSnapshot(ChapterRecord chapter, List<MediaSnapshot> mediaItems,
                                           List<String> warnings, String legacyDirKey) {
        return new ChapterSnapshot(chapter.getId(), MetadataScanSupport.versionOrZero(chapter.getVersion()),
                mediaItems, warnings, legacyDirKey);
    }

}
