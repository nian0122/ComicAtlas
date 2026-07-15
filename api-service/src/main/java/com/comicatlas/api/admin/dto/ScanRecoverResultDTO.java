package com.comicatlas.api.admin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScanRecoverResultDTO {
    private int scannedComics;
    private int existingComics;
    private int restoredComics;
    private int placeholderComics;
    private int restoredChapters;
    private int restoredPages;
    private List<String> placeholders = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
}
