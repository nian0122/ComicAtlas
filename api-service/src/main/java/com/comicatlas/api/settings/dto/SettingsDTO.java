package com.comicatlas.api.settings.dto;

import lombok.Data;

@Data
public class SettingsDTO {
    private String defaultQuality = "auto";
    private String defaultFit = "auto";
    private String defaultDirection = "vertical";
}
