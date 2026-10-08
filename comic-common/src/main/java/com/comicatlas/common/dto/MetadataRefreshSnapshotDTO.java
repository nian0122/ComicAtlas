package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 元数据扫盘刷新快照契约。 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MetadataRefreshSnapshotDTO {
    private final int schemaVersion; private final Long comicId; private final Instant generatedAt;
    private final String databaseRevision; private final List<ChapterSnapshot> chapters;
    @JsonCreator
    public MetadataRefreshSnapshotDTO(@JsonProperty("schemaVersion") int schemaVersion, @JsonProperty("comicId") Long comicId,
                                      @JsonProperty("generatedAt") Instant generatedAt, @JsonProperty("databaseRevision") String databaseRevision,
                                      @JsonProperty("chapters") List<ChapterSnapshot> chapters) { this.schemaVersion=schemaVersion; this.comicId=comicId; this.generatedAt=generatedAt; this.databaseRevision=databaseRevision; this.chapters=chapters; }
    public int schemaVersion(){return schemaVersion; } public Long comicId(){return comicId; } public Instant generatedAt(){return generatedAt; } public String databaseRevision(){return databaseRevision; } public List<ChapterSnapshot> chapters(){return chapters; }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class ChapterSnapshot {
        private final Long chapterId; private final int chapterVersion; private final List<MediaSnapshot> mediaItems; private final List<String> warnings; private final String legacyDirKey;
        @JsonCreator
        public ChapterSnapshot(@JsonProperty("chapterId") Long chapterId, @JsonProperty("chapterVersion") int chapterVersion, @JsonProperty("mediaItems") List<MediaSnapshot> mediaItems, @JsonProperty("warnings") List<String> warnings, @JsonProperty("legacyDirKey") String legacyDirKey) { this.chapterId=chapterId; this.chapterVersion=chapterVersion; this.mediaItems=mediaItems; this.warnings=warnings; this.legacyDirKey=legacyDirKey; }
        public ChapterSnapshot(Long chapterId, int chapterVersion, List<MediaSnapshot> mediaItems, List<String> warnings){this(chapterId, chapterVersion, mediaItems, warnings, null); }
        public Long chapterId(){return chapterId; } public int chapterVersion(){return chapterVersion; } public List<MediaSnapshot> mediaItems(){return mediaItems; } public List<String> warnings(){return warnings; } public String legacyDirKey(){return legacyDirKey; }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MediaSnapshot {
        private static final String LQ_STATUS_NOT_GENERATED="NOT_GENERATED";
        private final Long mediaId; private final int mediaVersion; private final String hqPath; private final String hqStatus; private final String lifecycleStatus; private final int pageNumber; private final long fileSize; private final String mediaType; private final Integer width; private final Integer height; private final BigDecimal duration; private final String container; private final String videoCodec; private final String audioCodec; private final String lqStatus; private final long lqSize; private final String lqPath;
        @JsonCreator
        public MediaSnapshot(@JsonProperty("mediaId") Long mediaId, @JsonProperty("mediaVersion") int mediaVersion, @JsonProperty("hqPath") String hqPath, @JsonProperty("hqStatus") String hqStatus, @JsonProperty("lifecycleStatus") String lifecycleStatus, @JsonProperty("pageNumber") int pageNumber, @JsonProperty("fileSize") long fileSize, @JsonProperty("mediaType") String mediaType, @JsonProperty("width") Integer width, @JsonProperty("height") Integer height, @JsonProperty("duration") BigDecimal duration, @JsonProperty("container") String container, @JsonProperty("videoCodec") String videoCodec, @JsonProperty("audioCodec") String audioCodec, @JsonProperty("lqStatus") String lqStatus, @JsonProperty("lqSize") long lqSize, @JsonProperty("lqPath") String lqPath) { RelativePathValidator.requireRelativeForwardSlash(hqPath); if(lqPath!=null){RelativePathValidator.requireRelativeForwardSlash(lqPath); } this.mediaId=mediaId; this.mediaVersion=mediaVersion; this.hqPath=hqPath; this.hqStatus=hqStatus; this.lifecycleStatus=lifecycleStatus; this.pageNumber=pageNumber; this.fileSize=fileSize; this.mediaType=mediaType; this.width=width; this.height=height; this.duration=duration; this.container=container; this.videoCodec=videoCodec; this.audioCodec=audioCodec; this.lqStatus=lqStatus; this.lqSize=lqSize; this.lqPath=lqPath; }
        public MediaSnapshot(Long id, int version, String path, String hs, String lifecycle, int page, long size, String type, Integer width, Integer height, BigDecimal duration, String container, String video, String audio, String lq, long lqSize){this(id, version, path, hs, lifecycle, page, size, type, width, height, duration, container, video, audio, lq, lqSize, null); }
        public MediaSnapshot(Long id, int version, String path, String hs, String lifecycle, int page, long size, String type, Integer width, Integer height, BigDecimal duration, String container, String video, String audio){this(id, version, path, hs, lifecycle, page, size, type, width, height, duration, container, video, audio, LQ_STATUS_NOT_GENERATED, 0L, null); }
        public Long mediaId(){return mediaId; } public int mediaVersion(){return mediaVersion; } public String hqPath(){return hqPath; } public String hqStatus(){return hqStatus; } public String lifecycleStatus(){return lifecycleStatus; } public int pageNumber(){return pageNumber; } public long fileSize(){return fileSize; } public String mediaType(){return mediaType; } public Integer width(){return width; } public Integer height(){return height; } public BigDecimal duration(){return duration; } public String container(){return container; } public String videoCodec(){return videoCodec; } public String audioCodec(){return audioCodec; } public String lqStatus(){return lqStatus; } public long lqSize(){return lqSize; } public String lqPath(){return lqPath; }
    }
}
