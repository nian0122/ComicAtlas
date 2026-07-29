package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;

public interface RecoveryTaskService {

    RecoveryTaskVO createRecoveryTask();

    IPage<RecoveryTaskVO> listTasks(Integer page, Integer size);

    RecoveryTaskVO getTaskDetail(Long id);

    RecoveryTaskVO retryTask(Long id);

    /** 供事件处理器更新任务进度计数器及状态 */
    void updateTask(RecoveryTaskVO vo);
}
