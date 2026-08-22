package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.metadata.ComicInfoXmlBuilder;
import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ComicInfoXmlBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsEscapedComicInfoFields() {
        ComicRecord comic = new ComicRecord();
        comic.setTitle("系列 & <特别>");
        comic.setAuthor("作者\"A");
        comic.setTags(List.of("动作", "冒险"));
        ChapterRecord chapter = new ChapterRecord();
        chapter.setTitle("第 1 话");
        chapter.setChapterNo("1");

        String xml = ComicInfoXmlBuilder.build(comic, List.of(chapter));

        assertTrue(xml.contains("<Series>系列 &amp; &lt;特别&gt;</Series>"));
        assertTrue(xml.contains("<Title>第 1 话</Title>"));
        assertTrue(xml.contains("<Writer>作者&quot;A</Writer>"));
        assertTrue(xml.contains("<Tags>动作, 冒险</Tags>"));
    }

    @Test
    void zipBuilderIncludesComicInfoXml() throws Exception {
        Path image = tempDir.resolve("001.jpg");
        Files.writeString(image, "image");
        ExportManifest manifest = new ExportManifest("漫画", "{}", "<ComicInfo/>",
                List.of(new ExportManifest.Entry("001.jpg", image, Files.size(image))));

        Path output = tempDir.resolve("漫画.zip");
        new ZipBuilder(new com.comicatlas.worker.config.WorkerConfig()).build(manifest, output);

        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            assertTrue(zip.getEntry("漫画/ComicInfo.xml") != null);
        }
    }
}
