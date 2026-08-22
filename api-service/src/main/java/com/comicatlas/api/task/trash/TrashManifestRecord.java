package com.comicatlas.api.task.trash;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * TRASH 资产清单数据库实体。
 * <p>
 * 存不可变 manifest（API 写入），Worker 从 DB 只读后按清单移动文件；
 * actual.json 保持文件形式，由 Worker 写、API 以只读挂载访问。
 * 数据库实体（DO），禁止直接暴露给接口；对外使用 {@code dto/} 包对应 DTO。
 */
@Data
@TableName("trash_manifest")
public class TrashManifestRecord {

    /** 管理任务 ID（无自增，复用 management_task.id） */
    @TableId(type = IdType.INPUT)
    private Long taskId;

    /** 目标类型：COMIC/CHAPTER/MEDIA */
    private String targetType;

    /** 目标实体 ID */
    private Long targetId;

    /** 不可变 TRASH 清单 JSON（TrashManifestDTO） */
    private String manifestJson;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
