package com.comicatlas.api.recovery.trash;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 永久清理请求体 — 二次确认 token 必填。
 */
@Data
public class PurgeRequest {

    @NotBlank(message = "二次确认 token 必填")
    private String token;
}
