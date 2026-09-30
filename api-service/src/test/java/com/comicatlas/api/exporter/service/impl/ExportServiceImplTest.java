package com.comicatlas.api.exporter.service.impl;

import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.storage.config.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.exporter.dto.ExportTaskVO;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * 导出服务测试 — 重点锁定 toVO 的物理路径解析：不再把逻辑 EXPORT 根当物理目录拼接，
 * 而是通过 ApiStorageProperties.root("EXPORT").resolve(outputPath) 得到真实路径。
 */
class ExportServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void createBatchDirectoryExportTask_locksAllComicsAndPersistsSelection() {
        Comic firstComic = new Comic();
        firstComic.setId(42L);
        firstComic.setStatus(ComicStatus.READY);
        Comic secondComic = new Comic();
        secondComic.setId(43L);
        secondComic.setStatus(ComicStatus.READY);
        ComicMapper comicMapper = mock(ComicMapper.class);
        when(comicMapper.selectByIdForUpdate(42L)).thenReturn(firstComic);
        when(comicMapper.selectByIdForUpdate(43L)).thenReturn(secondComic);
        ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);
        doAnswer(invocation -> {
            ((ExportTask) invocation.getArgument(0)).setId(71L);
            return 1;
        }).when(taskMapper).insert(any(ExportTask.class));
        ManagementTaskService managementTaskService = mock(ManagementTaskService.class);
        ManagementTaskResponse managementTask = new ManagementTaskResponse();
        managementTask.setId(81L);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(managementTask);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of());
        ExportServiceImpl exportService = new ExportServiceImpl(comicMapper, taskMapper,
                mock(OutboxService.class), managementTaskService, props);

        ExportTaskVO result = exportService.createBatchDirectoryExportTask(List.of(43L, 42L));

        assertThat(firstComic.getStatus()).isEqualTo(ComicStatus.EXPORTING);
        assertThat(secondComic.getStatus()).isEqualTo(ComicStatus.EXPORTING);
        assertThat(result.getComicIds()).containsExactly(42L, 43L);
        verify(comicMapper).updateById(firstComic);
        verify(comicMapper).updateById(secondComic);
        org.mockito.ArgumentCaptor<ExportTask> exportTaskCaptor = org.mockito.ArgumentCaptor.forClass(ExportTask.class);
        verify(taskMapper).insert(exportTaskCaptor.capture());
        assertThat(exportTaskCaptor.getValue().getComicIds()).isEqualTo("42,43");
        assertThat(exportTaskCaptor.getValue().getFormat()).isEqualTo(ExportFormats.BATCH_DIRECTORY);
    }

    @Test
    void createZipExportTask_doesNotLockComicForMoveOut() {
        Comic comic = new Comic();
        comic.setId(42L);
        comic.setStatus(ComicStatus.READY);
        ComicMapper comicMapper = mock(ComicMapper.class);
        when(comicMapper.selectByIdForUpdate(42L)).thenReturn(comic);
        ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);
        doAnswer(invocation -> {
            ((ExportTask) invocation.getArgument(0)).setId(72L);
            return 1;
        }).when(taskMapper).insert(any(ExportTask.class));
        ManagementTaskService managementTaskService = mock(ManagementTaskService.class);
        ManagementTaskResponse managementTask = new ManagementTaskResponse();
        managementTask.setId(82L);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(managementTask);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of());
        ExportServiceImpl exportService = new ExportServiceImpl(comicMapper, taskMapper,
                mock(OutboxService.class), managementTaskService, props);

        exportService.createExportTask(42L, ExportFormats.ZIP);

        assertThat(comic.getStatus()).isEqualTo(ComicStatus.READY);
        verify(comicMapper, never()).updateById(comic);
    }

    private ExportServiceImpl service(ExportTask task) {
        ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);
        when(taskMapper.selectById(task.getId())).thenReturn(task);

        ApiStorageRoot exportRoot = new ApiStorageRoot();
        exportRoot.setPath(tempDir);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of("EXPORT", exportRoot));

        return new ExportServiceImpl(mock(ComicMapper.class), taskMapper,
                mock(OutboxService.class), mock(ManagementTaskService.class), props);
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
        when(taskMapper.selectAllOrderByCreatedAtDesc()).thenReturn(List.of(task));

        ApiStorageRoot exportRoot = new ApiStorageRoot();
        exportRoot.setPath(tempDir);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of("EXPORT", exportRoot));

        ExportServiceImpl svc = new ExportServiceImpl(mock(ComicMapper.class), taskMapper,
                mock(OutboxService.class), mock(ManagementTaskService.class), props);

        List<ExportTaskVO> vos = svc.listAllExports();

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getId()).isEqualTo(7L);
        assertThat(vos.get(0).getComicId()).isEqualTo(42L);
        assertThat(vos.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(vos.get(0).getPhysicalPath()).isEqualTo(tempDir.resolve("7/base.zip").toString());
    }
}
