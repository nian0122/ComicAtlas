package com.comicatlas.api.importer.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchImportResultVO {
    private String batchId;
    private int total;
    private List<ImportTaskVO> succeeded;
    private List<FailedItem> failed;
}
