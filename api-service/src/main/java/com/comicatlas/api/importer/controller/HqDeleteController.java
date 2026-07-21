package com.comicatlas.api.importer.controller;
import com.comicatlas.api.importer.exception.HqDeletePreconditionException;
import com.comicatlas.api.importer.service.HqDeleteResult;
import com.comicatlas.api.importer.service.HqDeleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HqDeleteController {
    private final HqDeleteService hqDeleteService;

    @PostMapping("/api/comics/{comicId}/delete-hq")
    public ResponseEntity<?> deleteComicHq(@PathVariable Long comicId) {
        log.info("请求删除漫画 HQ: comicId={}", comicId);
        HqDeleteResult result = hqDeleteService.deleteForComic(comicId);
        return result == HqDeleteResult.ALREADY_DELETED
            ? ResponseEntity.ok().build()
            : ResponseEntity.accepted().build();
    }

    @PostMapping("/api/chapters/{chapterId}/delete-hq")
    public ResponseEntity<?> deleteChapterHq(@PathVariable Long chapterId) {
        log.info("请求删除章节 HQ: chapterId={}", chapterId);
        HqDeleteResult result = hqDeleteService.deleteForChapter(chapterId);
        return result == HqDeleteResult.ALREADY_DELETED
            ? ResponseEntity.ok().build()
            : ResponseEntity.accepted().build();
    }

    @ExceptionHandler(HqDeletePreconditionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handlePreconditionException(HqDeletePreconditionException e) {
        return Map.of("message", e.getMessage(), "pages", e.getDetails());
    }
}
