package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 导入用例使用的持久化无关模型，防止 ORM Entity 越过基础设施边界。 */
public final class ImportPersistenceModel {
    private ImportPersistenceModel() {
    }

    @Data
    public static class ImportTaskModel {
        private Long id;
        private Long managementTaskId;
        private Long comicId;
        private String sourceRef;
        private SourceType sourceType;
        private String sourcePath;
        private String batchId;
        private ImportTaskStatus status;
        private Integer progress;
        private Integer totalPages;
        private Integer downloadedPages;
        private String downloadMethod;
        private Long downloadSpeed;
        private Integer etaSeconds;
        private String errorMessage;
        private Integer retryCount;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationMs;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ComicModel {
        private Long id;
        private String title;
        private String titleJpn;
        private String author;
        private String description;
        private Integer totalPages;
        private Long hqSize;
        private Long lqSize;
        private SourceType sourceType;
        private String sourceGalleryId;
        private String sourceGalleryToken;
        private String sourceRef;
        private String storagePolicy;
        private ComicStatus status;
        private Long categoryId;
        private String category;
        private LocalDateTime deletedAt;
        private LocalDateTime trashedAt;
    }

    @Data
    public static class CatalogModel {
        private Long id;
        private Long comicId;
        private Long parentId;
        private String title;
        private Integer sortOrder;
    }

    @Data
    public static class ChapterModel {
        private Long id;
        private Long comicId;
        private Long catalogId;
        private String title;
        private String chapterNo;
        private Integer pageCount;
        private Integer sortOrder;
        private Integer globalOrder;
        private ChapterLifecycleStatus status;
        private LocalDateTime trashedAt;
    }

    @Data
    public static class MediaModel {
        private Long id;
        private Long chapterId;
        private Integer pageNumber;
        private Integer originalPageNumber;
        private String hqRoot;
        private String hqPath;
        private String lqRoot;
        private String lqPath;
        private HqStatus hqStatus;
        private LqStatus lqStatus;
        private TranscodeStatus transcodeStatus;
        private MediaLifecycleStatus status;
        private LocalDateTime trashedAt;
        private Long lqSize;
        private Integer width;
        private Integer height;
        private Long hqSize;
        private String mediaType;
        private BigDecimal duration;
        private String container;
        private String videoCodec;
        private String audioCodec;
    }

    @Data
    public static class TagModel {
        private Long id;
        private String name;
        private String type;
    }

    @Data
    public static class ComicTagModel {
        private Long comicId;
        private Long tagId;
    }
}
