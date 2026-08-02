package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class DirectoryScanRequest {
    private String parentPath;
    private String sourceType = "DIRECTORY";
}
