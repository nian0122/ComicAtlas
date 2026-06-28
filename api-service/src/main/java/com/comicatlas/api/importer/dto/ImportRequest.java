package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class ImportRequest {
    private String sourceRef;    // EHENTAI: gallery URL
    private String sourceType;   // EHENTAI / ZIP / DIRECTORY
    private String sourcePath;   // ZIP: file path, DIRECTORY: dir path
}
