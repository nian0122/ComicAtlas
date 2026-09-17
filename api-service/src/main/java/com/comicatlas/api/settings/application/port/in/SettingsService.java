package com.comicatlas.api.settings.application.port.in;

import com.comicatlas.api.settings.interfaces.rest.dto.SettingsDTO;

/** 应用设置服务。 */
public interface SettingsService {

    SettingsDTO getSettings();

    SettingsDTO updateSettings(SettingsDTO settings);
}
