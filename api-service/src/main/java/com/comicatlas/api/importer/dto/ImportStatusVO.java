package com.comicatlas.api.importer.dto;

import lombok.Data;

@Data
public class ImportStatusVO {
    private Long taskId;
    private String status;
    private Integer progress;
}
