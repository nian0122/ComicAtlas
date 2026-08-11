package com.comicatlas.api.export.service.impl;

import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.event.ExportEventPublisher;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出服务测试 — 重点锁定 toVO 的物理路径解析：不再把逻辑 EXPORT 根当物理目录拼接，
 * 而是通过 ApiStorageProperties.root("EXPORT").resolve(outputPath) 得到真实路径。
 */
class ExportServiceImplTest {

    @TempDir
    Path tempDir;

    private ExportServiceImpl service(ExportTask task) {
        ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);
        when(taskMapper.selectById(task.getId())).thenReturn(task);

        ApiStorageRoot exportRoot = new ApiStorageRoot();
        exportRoot.setPath(tempDir);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of("EXPORT", exportRoot));

        return new ExportServiceImpl(mock(ComicMapper.class), taskMapper,
                mock(ExportEventPublisher.class), mock(ManagementTaskService.class), props);
    }

    @Test
    void getTask_physicalPath通过EXPORT根解析输出路径() {
        ExportTask task = new ExportTask();
        task.setId(7L);
        task.setStatus(ExportTaskStatus.SUCCESS);
        task.setOutputRoot("EXPORT");
        task.setOutputPath("7/base.zip");
        task.setOutputSize(100L);

        ExportTaskVO vo = service(task).getTask(7L);

        assertThat(vo.getOutputRoot()).isEqualTo("EXPORT");
        assertThat(vo.getOutputPath()).isEqualTo("7/base.zip");
        assertThat(vo.getOutputSize()).isEqualTo(100L);
        assertThat(vo.getPhysicalPath()).isEqualTo(tempDir.resolve("7/base.zip").toString());
        assertThat(vo.getPhysicalPath()).doesNotStartWith("EXPORT");
        assertThat(vo.getPhysicalPath()).doesNotContain("..");
    }

    @Test
    void getTask_输出根为空时回退EXPORT根解析() {
        ExportTask task = new ExportTask();
        task.setId(8L);
        task.setStatus(ExportTaskStatus.SUCCESS);
        task.setOutputPath("old/base.zip");
        task.setOutputSize(50L);

        ExportTaskVO vo = service(task).getTask(8L);

        assertThat(vo.getPhysicalPath()).isEqualTo(tempDir.resolve("old/base.zip").toString());
    }

    @Test
    void getTask_无输出路径时物理路径为空() {
        ExportTask task = new ExportTask();
        task.setId(9L);
        task.setStatus(ExportTaskStatus.PENDING);

        ExportTaskVO vo = service(task).getTask(9L);

        assertThat(vo.getPhysicalPath()).isNull();
        assertThat(vo.getOutputPath()).isNull();
    }

    @Test
    void listAllExports_返回全部导出任务并解析物理路径() {
        ExportTask task = new ExportTask();
        task.setId(7L);
        task.setComicId(42L);
        task.setStatus(ExportTaskStatus.SUCCESS);
        task.setOutputRoot("EXPORT");
        task.setOutputPath("7/base.zip");
        task.setOutputSize(100L);

        ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        ApiStorageRoot exportRoot = new ApiStorageRoot();
        exportRoot.setPath(tempDir);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of("EXPORT", exportRoot));

        ExportServiceImpl svc = new ExportServiceImpl(mock(ComicMapper.class), taskMapper,
                mock(ExportEventPublisher.class), mock(ManagementTaskService.class), props);

        List<ExportTaskVO> vos = svc.listAllExports();

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getId()).isEqualTo(7L);
        assertThat(vos.get(0).getComicId()).isEqualTo(42L);
        assertThat(vos.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(vos.get(0).getPhysicalPath()).isEqualTo(tempDir.resolve("7/base.zip").toString());
    }
}
