package com.comicatlas.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 媒体上传/替换完成事件（Worker → API）。
 * <p>
 * Worker 分析 STAGING 文件并搬入 HQ 后回传每个媒体的分析结果，
 * API 依据结果将 STAGING media 更新为 READY 并写入尺寸/视频字段
 * （replace 流程保留 mediaId/pageNumber 并重置 LQ/transcode）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MediaUploadCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId; private final Long itemId; private final int attempt;
    private final String operationType; private final String targetType; private final Long targetId; private final List<MediaAnalysisResult> results;
    @JsonCreator
    public MediaUploadCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("version") int version,
                                     @JsonProperty("taskId") Long taskId, @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt,
                                     @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType, @JsonProperty("targetId") Long targetId,
                                     @JsonProperty("results") List<MediaAnalysisResult> results) { this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId; this.attempt=attempt; this.operationType=operationType; this.targetType=targetType; this.targetId=targetId; this.results=results; }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long itemId(){return itemId;} public int attempt(){return attempt;} public String operationType(){return operationType;} public String targetType(){return targetType;} public Long targetId(){return targetId;} public List<MediaAnalysisResult> results(){return results;}

    /**
     * 单个媒体文件分析结果。
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MediaAnalysisResult {
        private final Long mediaId; private final String mediaType; private final Integer width; private final Integer height; private final BigDecimal duration; private final String container; private final String videoCodec; private final String audioCodec; private final Long fileSize; private final String hqRoot; private final String hqPath;
        @JsonCreator
        public MediaAnalysisResult(@JsonProperty("mediaId") Long mediaId, @JsonProperty("mediaType") String mediaType, @JsonProperty("width") Integer width, @JsonProperty("height") Integer height, @JsonProperty("duration") BigDecimal duration, @JsonProperty("container") String container, @JsonProperty("videoCodec") String videoCodec, @JsonProperty("audioCodec") String audioCodec, @JsonProperty("fileSize") Long fileSize, @JsonProperty("hqRoot") String hqRoot, @JsonProperty("hqPath") String hqPath) { this.mediaId=mediaId; this.mediaType=mediaType; this.width=width; this.height=height; this.duration=duration; this.container=container; this.videoCodec=videoCodec; this.audioCodec=audioCodec; this.fileSize=fileSize; this.hqRoot=hqRoot; this.hqPath=hqPath; }
        public Long mediaId(){return mediaId;} public String mediaType(){return mediaType;} public Integer width(){return width;} public Integer height(){return height;} public BigDecimal duration(){return duration;} public String container(){return container;} public String videoCodec(){return videoCodec;} public String audioCodec(){return audioCodec;} public Long fileSize(){return fileSize;} public String hqRoot(){return hqRoot;} public String hqPath(){return hqPath;}
    }

    @Override
    public int version() {
        return version;
    }
}
