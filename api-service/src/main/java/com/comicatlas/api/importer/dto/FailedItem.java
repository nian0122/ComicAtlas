package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class FailedItem {
    private String sourcePath;
    private String errorMessage;
}
