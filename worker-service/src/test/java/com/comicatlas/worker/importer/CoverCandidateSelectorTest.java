package com.comicatlas.worker.importer;

import com.comicatlas.worker.importer.metadata.CoverCandidateSelector;
import com.comicatlas.worker.importer.metadata.CoverCandidateSelector.CoverCandidate;
import com.comicatlas.worker.importer.metadata.CoverCandidateSelector.MediaCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CoverCandidateSelector 纯逻辑单测：命名候选优先级、深度/自然序/globalOrder 排序、
 * cover-back 精确匹配防误判、全书图片兜底与视频抽帧兜底。
 */
class CoverCandidateSelectorTest {

    private final CoverCandidateSelector selector = new CoverCandidateSelector();

    private static MediaCandidate img(String sourceDir, String fileName, int globalOrder, int pageNumber) {
        return new MediaCandidate("IMAGE", globalOrder, pageNumber, sourceDir, fileName,
                globalOrder + "/" + pageNumber + "/" + fileName);
    }

    private static MediaCandidate img(String sourceDir, String fileName) {
        return img(sourceDir, fileName, 1, 1);
    }

    private static MediaCandidate vid(String sourceDir, String fileName, int globalOrder, int pageNumber) {
        return new MediaCandidate("VIDEO", globalOrder, pageNumber, sourceDir, fileName,
                globalOrder + "/" + pageNumber + "/" + fileName);
    }

    private static List<String> fileNames(List<CoverCandidate> candidates) {
        return candidates.stream().map(CoverCandidate::fileName).toList();
    }

    @Test
    void namedCandidates_winInFixedPriorityOrder() {
        List<CoverCandidate> result = selector.select(List.of(
                img(null, "folder.jpg"),
                img(null, "front.png"),
                img(null, "封面.jpg"),
                img(null, "cover.png"),
                img(null, "表紙.jpg")
        ));
        // cover(0)、封面(1)、表紙(2)、front(3)、folder(4)
        assertEquals(List.of("cover.png", "封面.jpg", "表紙.jpg", "front.png", "folder.jpg"),
                fileNames(result));
    }

    @Test
    void exactStemMatch_isCaseInsensitiveForLatinNames() {
        List<CoverCandidate> result = selector.select(List.of(
                img(null, "COVER.JPG"),
                img(null, "Front.PNG"),
                img(null, "FOLDER.bmp")
        ));
        assertEquals(List.of("COVER.JPG", "Front.PNG", "FOLDER.bmp"), fileNames(result));
    }

    @Test
    void sameNamePriority_sortedByDirectoryDepthAscending() {
        List<CoverCandidate> result = selector.select(List.of(
                img("vol1/ch1", "cover.jpg", 2, 1),
                img("vol1", "cover.png", 1, 1),
                img(null, "cover.gif", 1, 2)
        ));
        // 同属 cover 优先级，目录深度 0 < 1 < 2
        assertEquals(List.of("cover.gif", "cover.png", "cover.jpg"), fileNames(result));
    }

    @Test
    void samePriorityAndDepth_sortedByNaturalRelativePath() {
        List<CoverCandidate> result = selector.select(List.of(
                img("vol10", "cover.jpg", 1, 1),
                img("vol2", "cover.jpg", 1, 1)
        ));
        // 自然序：vol2 在 vol10 之前
        assertEquals(List.of("vol2/cover.jpg", "vol10/cover.jpg"),
                result.stream().map(CoverCandidate::naturalPath).toList());
    }

    @Test
    void samePriorityDepthPath_sortedByGlobalOrderThenPageNumber() {
        List<CoverCandidate> result = selector.select(List.of(
                img("vol1", "cover.jpg", 2, 1),
                img("vol1", "cover.jpg", 1, 2),
                img("vol1", "cover.jpg", 1, 1)
        ));
        assertEquals(List.of("1/1", "1/2", "2/1"),
                result.stream().map(c -> c.globalOrder() + "/" + c.pageNumber()).toList());
    }

    @Test
    void coverBackAndDiscover_areNotNamedCandidates() {
        List<CoverCandidate> result = selector.select(List.of(
                img(null, "cover-back.jpg"),
                img(null, "discover.jpg"),
                img(null, "cover.jpg")
        ));
        // cover-back / discover 主干不精确等于 cover，退化为全书图片兜底
        assertEquals(List.of("cover.jpg", "cover-back.jpg", "discover.jpg"), fileNames(result));
        assertEquals(0, result.get(0).priority(), "cover.jpg 应为命名候选");
        assertTrue(result.get(1).priority() > result.get(0).priority(), "cover-back 不应误判为命名候选");
    }

    @Test
    void onlyVideos_sortedByGlobalOrderThenPageNumber() {
        List<CoverCandidate> result = selector.select(List.of(
                vid(null, "ch2.mp4", 2, 1),
                vid(null, "ch1.mp4", 1, 1),
                vid(null, "ch1.mp4", 1, 2)
        ));
        assertEquals(List.of("1/1", "1/2", "2/1"),
                result.stream().map(c -> c.globalOrder() + "/" + c.pageNumber()).toList());
        assertTrue(result.stream().allMatch(c -> "VIDEO".equals(c.mediaType())), "候选应全部为视频");
    }

    @Test
    void imagesSelectedBeforeVideos() {
        List<CoverCandidate> result = selector.select(List.of(
                vid(null, "clip.mp4", 1, 1),
                img(null, "001.jpg"),
                img(null, "cover.jpg")
        ));
        // 命名图片 → 全书图片 → 视频抽帧
        assertEquals(List.of("cover.jpg", "001.jpg", "clip.mp4"), fileNames(result));
    }

    @Test
    void unsupportedExtension_isNotACandidate() {
        List<CoverCandidate> result = selector.select(List.of(
                new MediaCandidate("IMAGE", 1, 1, null, "cover.txt", null),
                img(null, "cover.jpg")
        ));
        assertEquals(List.of("cover.jpg"), fileNames(result), "txt 非受支持图片格式，不应成为候选");
    }

    @Test
    void nonImageMediaType_isNotAnImageCandidate() {
        List<CoverCandidate> result = selector.select(List.of(
                new MediaCandidate("VIDEO", 1, 1, null, "cover.jpg", "1/1/cover.jpg")
        ));
        // VIDEO 媒体即使名为 cover.jpg 也只能走视频兜底，不参与命名图片候选
        assertTrue(result.isEmpty(), "VIDEO 媒体配 jpg 扩展名不构成图片候选");
    }

    @Test
    void hqPathIsPreserved() {
        List<CoverCandidate> result = selector.select(List.of(img("vol1", "cover.jpg", 1, 1)));
        assertEquals(1, result.size());
        assertEquals("1/1/cover.jpg", result.get(0).hqPath(), "hqPath 应原样透传");
    }

    @Test
    void emptyInput_returnsEmptyList() {
        assertTrue(selector.select(List.of()).isEmpty());
    }

    @Test
    void nullOrBlankEntries_areIgnored() {
        // List.of 禁止 null，改用 Arrays.asList 构造含 null 的输入
        List<MediaCandidate> media = java.util.Arrays.asList(
                null,
                new MediaCandidate("IMAGE", 1, 1, null, "", null),
                img(null, "cover.jpg"));
        List<CoverCandidate> result = selector.select(media);
        assertEquals(List.of("cover.jpg"), fileNames(result));
    }
}
