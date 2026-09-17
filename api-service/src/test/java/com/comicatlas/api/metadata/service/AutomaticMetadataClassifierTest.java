package com.comicatlas.api.metadata.application.service;

import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort.CategorySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("导入自动元数据分类器")
class AutomaticMetadataClassifierTest {

    @Test
    @DisplayName("缺少分类时选择已有分类中最长的标题匹配项，并推断受控标签")
    void classify_missingCategory_matchesExistingCategoryAndInfersTags() {
        CategorySnapshot general = category(1L, "漫画");
        CategorySnapshot colorComic = category(2L, "彩色漫画");

        AutomaticMetadataClassifier.Enrichment result = AutomaticMetadataClassifier.classify(
                null, List.of(general, colorComic), "彩色漫画合集", null,
                "作者", "短篇作品", List.of());

        assertThat(result.category()).isSameAs(colorComic);
        assertThat(result.inferredTags()).containsExactlyInAnyOrder("彩色", "合集", "短篇");
        assertThat(result.inferredTagType()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("显式分类和显式标签优先，不重复推断同名标签")
    void classify_explicitValuesTakePrecedence() {
        CategorySnapshot category = category(3L, "同人");

        AutomaticMetadataClassifier.Enrichment result = AutomaticMetadataClassifier.classify(
                "同人", List.of(category), "彩色漫画", null,
                null, null, List.of("彩色"));

        assertThat(result.category()).isSameAs(category);
        assertThat(result.inferredTags()).doesNotContain("彩色");
    }

    @Test
    @DisplayName("没有可靠匹配时不自动创建分类或标签")
    void classify_unknownMetadata_returnsEmptyEnrichment() {
        AutomaticMetadataClassifier.Enrichment result = AutomaticMetadataClassifier.classify(
                null, List.of(), "普通作品", null, null, null, List.of());

        assertThat(result.category()).isNull();
        assertThat(result.inferredTags()).isEmpty();
    }

    private static CategorySnapshot category(Long id, String name) {
        return new CategorySnapshot(id, name, 1);
    }
}
