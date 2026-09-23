package com.comicatlas.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisionAnalyzerTest {
    @Test
    void removesMarkdownFenceAndSurroundingTextFromModelJson() {
        String normalized = VisionAnalyzer.normalizeJson(
                "模型结果如下：\n```json\n{\"tags\":[\"动作\"]}\n```\n");

        assertThat(normalized).isEqualTo("{\"tags\":[\"动作\"]}");
    }

    @Test
    void extractsFirstCompleteJsonObjectFromChinesePrefix() {
        String normalized = VisionAnalyzer.normalizeJson(
                "根据提供的分批结果，最终分类如下：{\"tags\":[\"校园\"]}。以上。\n");

        assertThat(normalized).isEqualTo("{\"tags\":[\"校园\"]}");
    }
}
