package com.comicatlas.common.constant;

/**
 * 存储策略（comic.storage_policy）常量（契约与 AGENTS.md「STORAGE」一致）。
 * <p>
 * 所有漫画统一 MANAGED 存储——文件搬入 {MANGA_ROOT}/hq/{comicId}/{chapterId}/，
 * 业务代码禁止硬编码存储策略字符串。
 */
public final class StoragePolicies {
    private StoragePolicies() {
    }

    /** MANAGED：托管存储，文件由平台搬入受控存储卷。 */
    public static final String MANAGED = "MANAGED";
}
