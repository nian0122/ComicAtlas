package com.comicatlas.worker.exporter.metadata;

import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;

import java.util.List;

/** 将数据库元数据转换为 ComicInfo.xml，供 CBZ 阅读器识别。 */
public final class ComicInfoXmlBuilder {

    private ComicInfoXmlBuilder() {
    }

    public static String build(ComicRecord comic, List<ChapterRecord> chapters) {
        ChapterRecord first = chapters == null || chapters.isEmpty() ? null : chapters.get(0);
        String series = value(comic == null ? null : comic.getTitle());
        String title = first == null ? series : value(first.getTitle());
        String number = first == null ? "" : value(first.getChapterNo());
        String writer = comic == null ? "" : value(comic.getAuthor());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ComicInfo>\n"
                + "  <Series>" + escape(series) + "</Series>\n"
                + "  <Title>" + escape(title) + "</Title>\n"
                + "  <Number>" + escape(number) + "</Number>\n"
                + "  <Writer>" + escape(writer) + "</Writer>\n"
                + "  <Summary>" + escape(value(comic == null ? null : comic.getDescription())) + "</Summary>\n"
                + "  <Tags>" + escape(comic == null || comic.getTags() == null
                ? "" : String.join(", ", comic.getTags())) + "</Tags>\n"
                + "</ComicInfo>\n";
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
