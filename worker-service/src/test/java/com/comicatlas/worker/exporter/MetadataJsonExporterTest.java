package com.comicatlas.worker.exporter;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.exporter.metadata.MetadataJsonExporter;
import com.comicatlas.worker.exporter.metadata.MetadataModelMapper;
import com.comicatlas.worker.exporter.model.ExportCollectResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 采集结果复用与元数据刷新入口兼容性。 */
class MetadataJsonExporterTest {
    @Test
    void existingCollectionAvoidsQueryButStandaloneRefreshStillCollects() {
        ExportCollector collector = mock(ExportCollector.class);
        MetadataModelMapper mapper = mock(MetadataModelMapper.class);
        MetadataJsonBuilder builder = mock(MetadataJsonBuilder.class);
        ExportCollectResult collected = mock(ExportCollectResult.class);
        MetadataV3 metadata = mock(MetadataV3.class);
        when(mapper.toV3(collected)).thenReturn(metadata);
        when(builder.build(metadata)).thenReturn("{\"version\":3}");
        MetadataJsonExporter exporter = new MetadataJsonExporter(collector, mapper, builder);
        assertEquals("{\"version\":3}", exporter.exportJson(collected));
        verifyNoInteractions(collector);
        when(collector.collect(7L)).thenReturn(collected);
        assertEquals("{\"version\":3}", exporter.exportJson(7L));
        verify(collector).collect(7L);
    }
}
