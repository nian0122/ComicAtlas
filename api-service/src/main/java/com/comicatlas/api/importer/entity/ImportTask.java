package com.comicatlas.api.importer.entity;

import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import lombok.Data;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 导入任务（记录单次漫画导入的来源、进度与结果）。
 * <p>
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO/VO。
 */
@Data
@TableName("import_task")
public class ImportTask {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 management_task.id 一对一扩展（V12 列） */
    private Long managementTaskId;
    /** 目标漫画 ID（导入预创建） */
    private Long comicId;
    /** 来源引用：EHENTAI 为画廊 URL，目录/ZIP 为源路径 */
    private String sourceRef;
    /** 来源类型：ZIP/DIRECTORY/EHENTAI/DIRECTORY */
    private SourceType sourceType;
    /** 源文件或源目录路径 */
    private String sourcePath;
    /** 批量导入批次标识（同一批次共享 UUID） */
    private String batchId;
    /** 任务状态：PENDING/PARSING/IMPORTING/SUCCESS/FAILED/CANCELLED */
    private ImportTaskStatus status;
    /** 进度百分比 0-100 */
    private Integer progress;
    /** 总页数 */
    private Integer totalPages;
    /** 已下载页数 */
    private Integer downloadedPages;
    /** 下载方式（默认 HTTP） */
    private String downloadMethod;
    /** 下载速度（字节/秒） */
    private Long downloadSpeed;
    /** 预计剩余秒数 */
    private Integer etaSeconds;
    /** 失败原因 */
    private String errorMessage;
    /** 重试次数 */
    private Integer retryCount;
    /** 开始时间 */
    private LocalDateTime startTime;
    /** 结束时间 */
    private LocalDateTime endTime;
    /** 总耗时（毫秒） */
    private Long durationMs;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
