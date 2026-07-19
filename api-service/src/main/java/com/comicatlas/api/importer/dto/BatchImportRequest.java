package com.comicatlas.api.importer.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchImportRequest {
    private String sourceType;
    private List<String> sourcePaths;
}
