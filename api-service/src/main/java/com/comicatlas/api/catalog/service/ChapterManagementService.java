package com.comicatlas.api.catalog.service;

import com.comicatlas.api.comic.dto.ChapterVO;
import com.comicatlas.api.comic.dto.ChapterCreateRequest;
import com.comicatlas.api.comic.dto.ChapterRenameRequest;

/**
 * 章节管理服务：create / rename / move / reorder / trash。
 *
 * <p>约束：
 * <ul>
 *   <li>所有 path ID 必须属于同一漫画，跨漫画一律 409</li>
 *   <li>{@code global_order} 全书连续 1..N、{@code sort_order} 目录内连续 1..M</li>
 *   <li>重排使用事务内两阶段更新（先临时偏移、再最终值），避免唯一键瞬时冲突</li>
 *   <li>章节 ID 不因移动/改名改变；Reader prev/next 仍只按 {@code global_order}</li>
 *   <li>并发写通过 {@code @Version} 乐观锁拒绝陈旧状态（409）</li>
 * </ul>
 */
public interface ChapterManagementService {

    ChapterVO createChapter(Long comicId, ChapterCreateRequest request);

    ChapterVO renameChapter(Long comicId, Long chapterId, ChapterRenameRequest request);

    /** @param catalogId 目标目录 ID，null 表示移动到根级 */
    ChapterVO moveChapter(Long comicId, Long chapterId, Long catalogId);

    /** @param targetGlobalOrder 全书目标位置（1 基） */
    ChapterVO reorderChapter(Long comicId, Long chapterId, int targetGlobalOrder);

    /** 回收：status → TRASHING（写入清单），Worker 移入 TRASH 后 → TRASHED */
    void trashChapter(Long comicId, Long chapterId);
}
