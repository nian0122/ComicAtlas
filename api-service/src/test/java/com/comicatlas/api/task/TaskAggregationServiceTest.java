package com.comicatlas.api.task;

import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.api.task.service.impl.TaskAggregationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskAggregationServiceTest {

    @Mock
    private ManagementTaskMapper taskMapper;

    @Mock
    private ManagementTaskItemMapper itemMapper;

    @InjectMocks
    private TaskAggregationServiceImpl taskAggregationService;

    @Test
    void aggregateDoesNotReopenTerminalTaskFromStaleRunningItemSnapshot() {
        ManagementTask task = new ManagementTask();
        task.setId(1665L);
        task.setStatus(ManagementTaskStatus.SUCCEEDED);
        ManagementTaskItem staleItem = new ManagementTaskItem();
        staleItem.setStatus(ManagementTaskStatus.RUNNING);
        when(itemMapper.selectByTaskId(1665L)).thenReturn(List.of(staleItem));
        when(taskMapper.selectById(1665L)).thenReturn(task);

        taskAggregationService.aggregate(1665L);

        verify(taskMapper, never()).updateById(task);
    }
}
