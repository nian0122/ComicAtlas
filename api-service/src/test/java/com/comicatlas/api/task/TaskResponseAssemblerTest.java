package com.comicatlas.api.task;

import com.comicatlas.api.task.assembler.TaskResponseAssembler;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseAssemblerTest {

    private final TaskResponseAssembler assembler = new TaskResponseAssembler();

    @Test
    void taskResponseTimesAreExplicitUtcInstants() {
        ManagementTask task = new ManagementTask();
        task.setCreatedAt(LocalDateTime.parse("2026-09-26T19:24:09"));

        ManagementTaskResponse response = assembler.toResponse(task);

        assertThat(response.getCreatedAt()).isEqualTo(Instant.parse("2026-09-26T19:24:09Z"));
    }

    @Test
    void taskItemResponseTimesAreExplicitUtcInstants() {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setStartedAt(LocalDateTime.parse("2026-09-26T19:23:39"));

        ManagementTaskItemResponse response = assembler.toItemResponse(item);

        assertThat(response.getStartedAt()).isEqualTo(Instant.parse("2026-09-26T19:23:39Z"));
    }
}
