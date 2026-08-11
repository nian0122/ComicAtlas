-- ============================================================
-- V18: TRASH 资产清单存数据库（API 只操作 DB，Worker 负责本地文件）
-- ============================================================
-- manifest_json：不可变回收清单（TrashManifestDTO JSON），API 写入，
--               Worker 从 DB 只读后按清单移动文件。
-- actual.json 保持文件：由 Worker 写（操作文件），API 以只读挂载访问。
-- ============================================================

CREATE TABLE trash_manifest (
    task_id        BIGINT       NOT NULL COMMENT '管理任务 ID（唯一）',
    target_type    VARCHAR(32)  NOT NULL COMMENT '目标类型：COMIC/CHAPTER/MEDIA',
    target_id      BIGINT       NOT NULL COMMENT '目标实体 ID',
    manifest_json  TEXT         NOT NULL COMMENT '不可变 TRASH 清单 JSON（TrashManifestDTO）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (task_id),
    KEY idx_target (target_type, target_id)
) COMMENT='TRASH 资产清单（API 写 DB，Worker 只读 DB + 操作文件）';
