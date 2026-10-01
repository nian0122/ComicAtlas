package com.comicatlas.api.library.dto;

import lombok.Data;

import java.util.List;

/** 管理端专属筛选协议，与阅读端的可阅读范围及分页策略分离。 */
@Data
public class ManagementComicListQuery {
    private String keyword;
    /** 兼容已有单标签查询参数。 */
    private String tag;
    private List<String> tags;
    /** OR 任一、AND 全部、NOT 排除；_NONE 表示没有标签。 */
    private String tagMode = "OR";
    /** 未指定时不限制漫画生命周期。 */
    private String status;
    /** 分类名称，_NONE 表示未分类。 */
    private String category;
    private String sourceType;
    /** HAS_HQ 任一可用、ALL_HQ 全部可用、PARTIAL_HQ 部分可用、NO_HQ 均不可用。 */
    private String hqStatus;
    /** 只统计活动图片；QUEUED 包含排队及生成中，GENERATING 只匹配生成中。 */
    private String lqStatus;
    private String sort = "createdAt";
    private String order = "desc";
    private Integer page = 1;
    private Integer size = 20;

    /** 去重后的标签数用于 AND 条件的 SQL 参数绑定。 */
    public int getTagCount() {
        return tags == null ? 0 : tags.size();
    }
}
