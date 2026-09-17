package com.comicatlas.api.importer.interfaces.rest.dto;

import lombok.Data;

@Data
public class ImportStatusVO {
    private Long taskId;
    private String status;
    private Integer progress;
}
