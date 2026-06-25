package com.comicatlas.api.operation.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.operation.dto.OperationLogVO;
import com.comicatlas.api.operation.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationLogService logService;

    @GetMapping
    public Result<IPage<OperationLogVO>> listLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String businessId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(logService.listLogs(module, action, businessId, keyword, page, size));
    }
}
