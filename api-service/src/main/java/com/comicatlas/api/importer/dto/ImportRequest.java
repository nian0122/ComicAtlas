package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class ImportRequest {
    private String sourceRef;    // EHENTAI: gallery URL
    private String sourceType;   // EHENTAI / ZIP / CBZ / DIRECTORY
    private String sourcePath;   // ZIP/CBZ: file path, DIRECTORY: dir path
}
