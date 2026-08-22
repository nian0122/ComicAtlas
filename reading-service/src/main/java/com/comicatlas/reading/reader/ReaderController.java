package com.comicatlas.reading.reader;

import com.comicatlas.contract.common.Result;
import com.comicatlas.reading.reader.ReaderDTO;
import com.comicatlas.reading.reader.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 章节阅读接口（阅读域）。
 * <p>
 * 基路径 {@code /api}，按章节加载阅读数据（页面列表 + 前/后章节导航），
 * 供阅读器渲染。页面路径由 FileUrlResolver 统一生成，不在此处拼 URL。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReaderController {

    private final ReaderService readerService;

    /**
     * 加载章节阅读数据。
     *
     * @param id 章节 ID
     * @return 阅读数据（pages + prev/next 章节引用）
     */
    @GetMapping("/chapters/{id}")
    public Result<ReaderDTO> getChapter(@PathVariable Long id) {
        return Result.ok(readerService.getChapter(id));
    }
}
