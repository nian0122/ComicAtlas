package com.comicatlas.api.comic.cache;

/**
 * 漫画领域缓存名常量。集中管理，供 @Cacheable / cacheManager / 失效器三方引用。
 */
public final class ComicReferenceCache {

    private ComicReferenceCache() {
    }

    /** 漫画目录树缓存（按 comicId）。 */
    public static final String CATALOG = "comicCatalog";

    /** 分类列表缓存（全量快照，key="all"）。 */
    public static final String CATEGORIES = "comicCategories";

    /** 标签列表缓存（全量快照，key="all"）。 */
    public static final String TAGS = "comicTags";

    /** 漫画列表查询缓存（组合键，短 TTL）。 */
    public static final String COMIC_LIST = "comicList";

    /** 存储统计缓存（含文件系统扫描的 thumb 大小，短 TTL）。 */
    public static final String STORAGE_STATS = "storageStats";

    /** 全量快照类缓存的统一 key。 */
    public static final String ALL_KEY = "all";
}
