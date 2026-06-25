package com.comicatlas.api.operation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.operation.dto.OperationLogVO;
import com.comicatlas.api.operation.entity.OperationLog;
import com.comicatlas.api.operation.mapper.OperationLogMapper;
import com.comicatlas.api.operation.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper logMapper;

    @Override
    public IPage<OperationLogVO> listLogs(String module, String action, String businessId, String keyword, Integer page, Integer size) {
        var wrapper = new LambdaQueryWrapper<OperationLog>()
            .eq(module != null, OperationLog::getModule, module)
            .eq(action != null, OperationLog::getAction, action)
            .eq(businessId != null, OperationLog::getBusinessId, businessId)
            .like(keyword != null, OperationLog::getDetail, keyword)
            .orderByDesc(OperationLog::getCreatedAt);
        Page<OperationLog> p = new Page<>(page != null ? page : 1, size != null ? size : 20);
        return logMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    private OperationLogVO toVO(OperationLog l) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(l.getId()); vo.setTraceId(l.getTraceId());
        vo.setModule(l.getModule()); vo.setAction(l.getAction());
        vo.setBusinessId(l.getBusinessId()); vo.setDetail(l.getDetail());
        vo.setCreatedAt(l.getCreatedAt());
        return vo;
    }
}
