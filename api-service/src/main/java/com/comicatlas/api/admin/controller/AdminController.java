package com.comicatlas.api.admin.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.admin.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuildFromHq() {
        return Result.ok(adminService.rebuildFromHq());
    }
}
