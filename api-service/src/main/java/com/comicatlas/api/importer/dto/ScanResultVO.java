package com.comicatlas.api.importer.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScanResultVO {
    private String parentPath;
    private int total;
    private List<ScanItemVO> items;
}
