package com.comicatlas.worker.importer;

import com.comicatlas.worker.importer.model.ComicInfoMetadata;
import com.comicatlas.worker.importer.model.DirectoryTree;
import com.comicatlas.worker.importer.model.ImportContext;
import com.comicatlas.worker.importer.parser.ComicInfoParser;
import com.comicatlas.worker.importer.metadata.MetadataAssembler;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComicInfoParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesStandardFieldsAndSplitsTags() throws Exception {
        Files.writeString(tempDir.resolve("ComicInfo.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <ComicInfo>
                  <Series>我的系列</Series>
                  <Title>第 01 话</Title>
                  <Number>1</Number>
                  <Writer>作者</Writer>
                  <Summary>这是简介</Summary>
                  <Genre>动作, 冒险</Genre>
                  <Tags>冒险;原创</Tags>
                </ComicInfo>
                """);

        ComicInfoMetadata metadata = ComicInfoParser.parse(tempDir).orElseThrow();

        assertEquals("我的系列", metadata.series());
        assertEquals("第 01 话", metadata.title());
        assertEquals("1", metadata.number());
        assertEquals("作者", metadata.author());
        assertEquals("这是简介", metadata.summary());
        assertEquals(java.util.List.of("动作", "冒险", "原创"), metadata.tags());
    }

    @Test
    void rejectsExternalEntities() throws Exception {
        Files.writeString(tempDir.resolve("comicinfo.xml"), """
                <!DOCTYPE ComicInfo [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <ComicInfo><Title>&secret;</Title></ComicInfo>
                """);

        assertThrows(IOException.class, () -> ComicInfoParser.parse(tempDir));
    }

    @Test
    void assemblerUsesComicInfoForComicAndSingleChapter() {
        MediaAnalyzer analyzer = mock(MediaAnalyzer.class);
        when(analyzer.analyze(any())).thenReturn(
                new ComicMetadata.MediaInfo("001.jpg", 1, "READY", "NOT_GENERATED", 10, 1, 1));
        DirectoryTree tree = new DirectoryTree(tempDir, "文件名", List.of(tempDir.resolve("001.jpg")), List.of());
        ComicMetadata metadata = new MetadataAssembler(analyzer).assemble(tree,
                new ImportContext("CBZ", tempDir, false, false, "文件名"),
                new ComicInfoMetadata("系列名", "单行标题", "2", "作者", "简介", List.of("动作")));

        assertEquals("系列名", metadata.title());
        assertEquals("作者", metadata.author());
        assertEquals("简介", metadata.description());
        assertEquals(List.of("动作"), metadata.tags());
        assertEquals("单行标题", metadata.chapters().get(0).title());
        assertEquals("2", metadata.chapters().get(0).chapterNo());
    }
}
