package com.comicatlas.api.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建标签请求。
 */
@Data
public class CreateTagRequest {

    /** 标签名称（必填，全局唯一） */
    @NotBlank(message = "标签名称不能为空")
    @Size(max = 255, message = "标签名称长度不能超过255")
    private String name;
}
