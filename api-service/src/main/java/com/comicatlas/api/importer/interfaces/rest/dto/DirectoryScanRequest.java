package com.comicatlas.api.importer.interfaces.rest.dto;

import lombok.Data;

@Data
public class DirectoryScanRequest {
    private String parentPath;
    private String sourceType = "DIRECTORY";
}
