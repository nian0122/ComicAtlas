package com.comicatlas.worker.importer;

import java.util.List;

/** ComicInfo.xml 中与 ComicAtlas 现有元数据模型对应的字段。 */
public record ComicInfoMetadata(
        String series,
        String title,
        String number,
        String author,
        List<String> tags
) {
}
