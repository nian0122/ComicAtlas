package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ComicMetadataUpdateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    @Size(max = 128, message = "作者长度不能超过128")
    private String author;

    @Size(max = 4000, message = "描述长度不能超过4000")
    private String description;
}