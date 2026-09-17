package com.comicatlas.api.importer.interfaces.rest.dto;

import lombok.Data;

@Data
public class FailedItem {
    private String sourcePath;
    private String errorMessage;
}
