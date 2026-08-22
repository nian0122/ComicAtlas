package com.comicatlas.api.settings.service;

import com.comicatlas.api.settings.dto.SettingsDTO;

/** 应用设置服务。 */
public interface SettingsService {

    SettingsDTO getSettings();

    SettingsDTO updateSettings(SettingsDTO settings);
}
