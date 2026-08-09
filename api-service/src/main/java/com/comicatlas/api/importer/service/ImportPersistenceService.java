package com.comicatlas.api.importer.service;

import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;

import java.util.List;
import java.util.Map;

/**
 * 导入两阶段落库服务（Wave 2→3）。
 * <p>
 * 职责单一：把导入任务的 DB 持久化与最终化编排从 {@code ImportEventHandler} 拆出。
 * <ul>
 *   <li>{@link #persistCompleted}：completed 事件短事务内校验 metadata、插入 catalog/chapter/media
 *       （media 一律 PENDING、comic/task 保持非终态），逐章产出最终化请求（经 Outbox 提交后发布）；</li>
 *   <li>{@link #applyFinalizeCompleted}：按事件 chapterId 将本章 media/chapter 置 READY（逐章确认，幂等）；
 *       全部章节 media 均 READY 后才把 comic → READY、task → SUCCESS，维护统计与缓存失效；</li>
 *   <li>{@link #applyFinalizeFailed}：明确标记失败且保持可重试，不得置 READY。</li>
 * </ul>
 * <p>
 * 事务内禁止文件移动/下载/解压/外部进程调用（阿里规范：事务内不得长 IO）。
 */
public interface ImportPersistenceService {

    /**
     * 单个章节的存储最终化请求描述（sourceDir/targetDir 均为相对 MANGA_ROOT 的路径）。
     */
    record FinalizeRequest(
            Long taskId,
            Long comicId,
            Integer globalOrder,
            Long chapterId,
            String sourceDir,
            String targetDir,
            List<FinalizeMediaMapping> mediaMappings
    ) {
    }

    /**
     * completed 阶段：短事务内校验并插入结构，逐章产出最终化请求。
     *
     * @param event    ImportTaskCompletedEvent（仅表示 staging/metadata 就绪）
     * @param metadata 已解析的 metadata.json 内容（事务外读取）
     * @return 逐章最终化请求（空表示幂等跳过）；请求已同步写入 Outbox，事务提交后由 relay 发布
     */
    List<FinalizeRequest> persistCompleted(ImportTaskCompletedEvent event, Map<String, Object> metadata);

    /**
     * finalize completed：使用事件真实 targetDir 将 media 置 READY，chapter/comic → READY、
     * task → SUCCESS，更新统计并失效目录缓存。幂等：重复/乱序事件安全跳过。
     */
    void applyFinalizeCompleted(ImportStorageFinalizeCompletedEvent event);

    /**
     * finalize failed：task → FAILED、comic IMPORTING → IMPORT_FAILED（均保留重试条件），
     * media 保持 PENDING 不置 READY。幂等：重复/乱序事件安全跳过。
     */
    void applyFinalizeFailed(ImportStorageFinalizeFailedEvent event);
}
