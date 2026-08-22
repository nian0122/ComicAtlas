package com.comicatlas.worker.shared.metadata;

/**
 * 元数据 JSON 导出能力的最小契约。
 *
 * <p>媒体刷新只依赖该契约，不直接依赖导出业务包，避免业务域反向耦合。</p>
 */
@FunctionalInterface
public interface MetadataExporter {

    /**
     * 生成指定漫画的元数据 JSON。
     *
     * @param comicId 漫画 ID
     * @return UTF-8 元数据 JSON 文本
     */
    String exportJson(Long comicId);
}
