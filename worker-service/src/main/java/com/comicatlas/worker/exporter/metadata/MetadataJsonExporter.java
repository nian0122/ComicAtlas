package com.comicatlas.worker.exporter.metadata;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.shared.metadata.MetadataExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 元数据 JSON 生成：collect → map → build（导出与元数据刷新共用）。 */
@Component
@RequiredArgsConstructor
public class MetadataJsonExporter implements MetadataExporter {

    private final ExportCollector exportCollector;
    private final MetadataModelMapper modelMapper;
    private final MetadataJsonBuilder metadataJsonBuilder;

    @Override
    public String exportJson(Long comicId) {
        return metadataJsonBuilder.build(modelMapper.toV3(exportCollector.collect(comicId)));
    }
}
