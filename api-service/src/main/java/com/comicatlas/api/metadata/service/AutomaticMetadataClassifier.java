package com.comicatlas.api.metadata.service;

import com.comicatlas.persistence.comic.entity.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导入阶段的确定性元数据推断器。
 *
 * <p>这是保守的补全策略：用户或 ComicInfo 已提供的分类/标签永远优先，
 * 分类只从现有分类词条中选择，自动标签只使用本类明确声明的关键词，
 * 因此不会改变用户维护的分类体系，也不会因外部服务波动导致导入结果变化。</p>
 */
public final class AutomaticMetadataClassifier {

    private static final String AUTO_TAG_TYPE = "AUTO";
    private static final Map<String, String> KEYWORD_TAGS = Map.ofEntries(
            Map.entry("彩色", "彩色"), Map.entry("全彩", "彩色"), Map.entry("カラー", "彩色"),
            Map.entry("黑白", "黑白"), Map.entry("白黑", "黑白"), Map.entry("モノクロ", "黑白"),
            Map.entry("短篇", "短篇"), Map.entry("短編", "短篇"), Map.entry("one-shot", "短篇"),
            Map.entry("长篇", "长篇"), Map.entry("長編", "长篇"), Map.entry("连载", "连载"),
            Map.entry("合集", "合集"), Map.entry("anthology", "合集"),
            Map.entry("动画", "动画"), Map.entry("anime", "动画"), Map.entry("video", "视频"),
            Map.entry("视频", "视频"), Map.entry("4k", "4K"));

    private AutomaticMetadataClassifier() {
    }

    /**
     * 根据导入元数据推断分类和标签。
     *
     * @param explicitCategory 元数据显式分类
     * @param categories 当前数据库中的分类
     * @param title 标题
     * @param titleJpn 日文标题
     * @param author 作者
     * @param description 简介
     * @param importedTags 元数据显式标签
     */
    public static Enrichment classify(String explicitCategory, List<Category> categories,
                                      String title, String titleJpn, String author,
                                      String description, List<String> importedTags) {
        List<Category> availableCategories = categories == null ? List.of() : categories;
        String explicitValue = normalize(explicitCategory);
        Category selectedCategory = findCategory(explicitValue, availableCategories);
        String searchableText = joinText(title, titleJpn, author, description, importedTags);
        if (selectedCategory == null && explicitValue == null) {
            selectedCategory = availableCategories.stream()
                    .filter(category -> normalize(category.getName()) != null)
                    .filter(category -> containsIgnoreCase(searchableText, category.getName()))
                    .sorted(Comparator.comparingInt((Category category) -> normalize(category.getName()).length()).reversed())
                    .findFirst()
                    .orElse(null);
        }

        Set<String> importedNames = new LinkedHashSet<>();
        if (importedTags != null) {
            importedTags.stream().map(AutomaticMetadataClassifier::normalize)
                    .filter(value -> value != null).forEach(importedNames::add);
        }
        Set<String> inferredTags = new LinkedHashSet<>();
        KEYWORD_TAGS.forEach((keyword, tagName) -> {
            if (containsIgnoreCase(searchableText, keyword) && !importedNames.contains(tagName)) {
                inferredTags.add(tagName);
            }
        });
        return new Enrichment(selectedCategory, List.copyOf(inferredTags), AUTO_TAG_TYPE);
    }

    private static Category findCategory(String explicitCategory, List<Category> categories) {
        if (explicitCategory == null) {
            return null;
        }
        return categories.stream()
                .filter(category -> explicitCategory.equalsIgnoreCase(normalize(category.getName())))
                .findFirst().orElse(null);
    }

    private static String joinText(String title, String titleJpn, String author, String description,
                                   List<String> importedTags) {
        List<String> values = new ArrayList<>();
        values.add(title); values.add(titleJpn); values.add(author); values.add(description);
        if (importedTags != null) {
            values.addAll(importedTags);
        }
        return values.stream().map(AutomaticMetadataClassifier::normalize)
                .filter(value -> value != null).reduce("", (left, right) -> left + " " + right);
    }

    private static boolean containsIgnoreCase(String text, String value) {
        String normalizedValue = normalize(value);
        return normalizedValue != null && text.toLowerCase(Locale.ROOT).contains(normalizedValue.toLowerCase(Locale.ROOT));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 推断结果；分类为空表示没有可靠匹配。 */
    public record Enrichment(Category category, List<String> inferredTags, String inferredTagType) {
    }
}
