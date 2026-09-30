package com.comicatlas.contract.comic.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComicListQuery {
    private String keyword;
    private String tag;
    private List<String> tags;
    /** 标签筛选模式：OR 任一、AND 全部、NOT 排除所选标签。 */
    private String tagMode = "OR";
    private String status;
    private String category;
    private String sourceType;
    /** 漫画 HQ 聚合筛选：HAS_HQ=存在 HQ 文件，NO_HQ=不存在 HQ 文件。 */
    private String hqStatus;
    /** 漫画 LQ 筛选：QUEUED 匹配排队或生成中的活动媒体，GENERATING 仅匹配生成中的媒体。 */
    private String lqStatus;
    private String sort = "createdAt";
    private String order = "desc";
    private Integer page = 1;
    private Integer size = 20;

    /**
     * 返回标签筛选数量，供 MyBatis SQL 绑定使用。
     * 集合的 {@code size} 不是稳定的 JavaBean 属性，不能直接用于参数占位符。
     */
    public int getTagCount() {
        return tags == null ? 0 : tags.size();
    }
}
