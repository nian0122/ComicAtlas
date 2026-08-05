package com.comicatlas.api.management;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.SourceType;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.export.service.ExportService;
import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.api.importer.dto.ImportRequest;
import com.comicatlas.api.importer.dto.ImportTaskVO;
import com.comicatlas.api.importer.entity.DirectoryScanTask;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.event.ImportEventHandler;
import com.comicatlas.api.importer.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.importer.service.DirectoryScanTaskService;
import com.comicatlas.api.importer.service.ImportService;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.management.service.LegacyTaskBackfillService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.entity.InboxReceipt;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.rabbitmq.client.Channel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一任务兼容集成测试（TDD）。
 * <p>
 * 验证旧专表（import/export/recovery/directory_scan）与 management_task 主表：
 * <ul>
 *   <li>旧 ID 创建时同步建立 management_task，旧/新状态语义一致</li>
 *   <li>Export/scan/recovery 首次加载即在 /api/management/tasks 可见</li>
 *   <li>导入阶段 DOWNLOADING/EXTRACTING/PARSING → management_task.stage（TaskStage 枚举）</li>
 *   <li>import cancel 后迟到进度不回退，CANCELLED 为真正终态</li>
 *   <li>失败 item 可重试（attempt 递增）</li>
 *   <li>历史行回填数量一致</li>
 *   <li>按 type/status/batch/target 过滤</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("统一任务兼容集成测试")
class UnifiedTaskCompatibilityIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @Container
    static RabbitMQContainer rabbitmq = dockerAvailable
            ? new RabbitMQContainer("rabbitmq:3.12-management-alpine")
                .withAdminPassword("test_rabbit_pass")
            : null;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        }
        if (dockerAvailable && rabbitmq != null && rabbitmq.isRunning()) {
            registry.add("spring.rabbitmq.host", rabbitmq::getHost);
            registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
            registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
            registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ImportService importService;
    @Autowired private ExportService exportService;
    @Autowired private RecoveryTaskService recoveryTaskService;
    @Autowired private DirectoryScanTaskService directoryScanTaskService;
    @Autowired private ManagementTaskService managementTaskService;
    @Autowired private LegacyTaskBackfillService backfillService;
    @Autowired private ImportEventHandler importEventHandler;

    @Autowired private ImportTaskMapper importTaskMapper;
    @Autowired private ExportTaskMapper exportTaskMapper;
    @Autowired private RecoveryTaskMapper recoveryTaskMapper;
    @Autowired private DirectoryScanTaskMapper directoryScanTaskMapper;
    @Autowired private ManagementTaskMapper managementTaskMapper;
    @Autowired private ManagementTaskItemMapper managementTaskItemMapper;
    @Autowired private ComicMapper comicMapper;
    @Autowired private OutboxMessageMapper outboxMessageMapper;
    @Autowired private InboxReceiptMapper inboxReceiptMapper;

    @AfterEach
    void tearDown() {
        if (outboxMessageMapper != null) { outboxMessageMapper.delete(new LambdaQueryWrapper<>()); }
        if (inboxReceiptMapper != null) { inboxReceiptMapper.delete(new LambdaQueryWrapper<>()); }
        if (importTaskMapper != null) { importTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (recoveryTaskMapper != null) { recoveryTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (exportTaskMapper != null) { exportTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (directoryScanTaskMapper != null) { directoryScanTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (managementTaskMapper != null) { managementTaskMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
    }

    private static boolean checkDockerAvailable() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            return true;
        }
        try {
            new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
                .waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== Import 同步 ========================

    @Test
    @DisplayName("导入任务创建时同步建立 management_task，旧/新状态一致")
    void importTask_create_syncsManagementTask_andOldNewStatusConsistent() {
        ImportTaskVO vo = importService.createImportTask(buildRequest("/mnt/import/测试漫画A"), null);
        ImportTask it = importTaskMapper.selectById(vo.getId());

        assertThat(it.getManagementTaskId()).isNotNull();

        ManagementTaskResponse mt = managementTaskService.getTask(it.getManagementTaskId());
        assertThat(mt.getTaskType()).isEqualTo(TaskType.IMPORT);
        assertThat(mt.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
        assertThat(mt.getOperation()).isEqualTo("导入漫画");

        // 旧 endpoint 状态（PENDING）与统一任务（QUEUED）语义一致
        var oldStatus = importService.getTaskStatus(vo.getId());
        assertThat(oldStatus.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("导入阶段事件写入 management_task.stage（TaskStage）并转 RUNNING")
    void importStageEvent_updatesManagementTaskStage() {
        ImportTaskVO vo = importService.createImportTask(buildRequest("/mnt/import/测试漫画B"), null);
        ImportTask it = importTaskMapper.selectById(vo.getId());

        var stageEvent = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), it.getId(), "DOWNLOADING", 42, "HTTP", 1024, 7);
        importEventHandler.handleTaskStatusChanged(stageEvent, mock(Channel.class), 1L);

        ImportTask after = importTaskMapper.selectById(it.getId());
        assertThat(after.getStatus()).isEqualTo(ImportTaskStatus.PENDING);

        ManagementTask mt = managementTaskMapper.selectById(it.getManagementTaskId());
        assertThat(mt.getStage()).isEqualTo("DOWNLOADING");
        assertThat(mt.getStatus()).isEqualTo(ManagementTaskStatus.RUNNING);
        assertThat(mt.getProgress()).isEqualTo(42);
    }

    @Test
    @DisplayName("导入取消后为真正终态，迟到进度与状态不回退")
    void importCancel_isTrueTerminal_lateProgressIgnored() {
        ImportTaskVO vo = importService.createImportTask(buildRequest("/mnt/import/测试漫画C"), null);
        ImportTask it = importTaskMapper.selectById(vo.getId());

        // 先进入 RUNNING 阶段（模拟 Worker 已开始）
        var stageEvent = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), it.getId(), "PARSING", 5, null, 0, 0);
        importEventHandler.handleTaskStatusChanged(stageEvent, mock(Channel.class), 1L);

        importService.cancelTask(it.getId());

        ImportTask cancelled = importTaskMapper.selectById(it.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ImportTaskStatus.CANCELLED);

        ManagementTaskResponse mt = managementTaskService.getTask(cancelled.getManagementTaskId());
        assertThat(mt.getStatus()).isEqualTo(ManagementTaskStatus.CANCELLED);

        // 迟到阶段事件 → management_task 不回退（CANCELLED 为终态）
        var lateStage = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), cancelled.getId(), "DOWNLOADING", 80, null, 0, 0);
        importEventHandler.handleTaskStatusChanged(lateStage, mock(Channel.class), 1L);
        assertThat(importTaskMapper.selectById(cancelled.getId()).getStatus()).isEqualTo(ImportTaskStatus.CANCELLED);

        // 迟到 item 进度 → 忽略
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(cancelled.getManagementTaskId());
        assertThat(items).isNotEmpty();
        boolean applied = managementTaskService.updateItemProgress(items.get(0).getId(), 1, 90, "DOWNLOADING");
        assertThat(applied).isFalse();
        ManagementTaskResponse after = managementTaskService.getTask(cancelled.getManagementTaskId());
        assertThat(after.getStatus()).isEqualTo(ManagementTaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("失败 item 可重试：attempt 递增并回到 QUEUED")
    void failedItem_canBeRetried() {
        ImportTaskVO vo = importService.createImportTask(buildRequest("/mnt/import/测试漫画D"), null);
        ImportTask it = importTaskMapper.selectById(vo.getId());

        com.comicatlas.api.management.entity.ManagementTaskItem activeItem =
                managementTaskService.findActiveItem("COMIC", it.getComicId(), TaskType.IMPORT);
        assertThat(activeItem).isNotNull();
        managementTaskService.updateItemStatus(activeItem.getId(), ManagementTaskStatus.FAILED,
                "模拟导入失败", "IMPORT_TASK", it.getId());

        ManagementTaskResponse failedTask = managementTaskService.getTask(it.getManagementTaskId());
        assertThat(failedTask.getStatus()).isEqualTo(ManagementTaskStatus.FAILED);
        assertThat(failedTask.getFailureCount()).isEqualTo(1);

        ManagementTaskResponse retried = managementTaskService.retryTask(it.getManagementTaskId());
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(retried.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
        assertThat(retried.getFailureCount()).isZero();
    }

    // ======================== Export / Scan / Recovery 首屏可见 ========================

    @Test
    @DisplayName("导出任务创建后首屏即可在 /api/management/tasks 发现（EXPECTED_FAIL_BEFORE_FIX）")
    void exportTask_appearsInUnifiedList_onFirstLoad() throws Exception {
        Comic comic = insertReadyComic("导出漫画E");

        var export = exportService.createExportTask(comic.getId());
        ExportTask et = exportTaskMapper.selectById(export.getId());
        assertThat(et.getManagementTaskId()).isNotNull();

        // 统一任务列表按 type=EXPORT 可见
        mockMvc.perform(get("/api/management/tasks").param("type", "EXPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[*].taskType").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("EXPORT"))));
    }

    @Test
    @DisplayName("目录扫描任务创建后统一任务列表可见")
    void scanTask_appearsInUnifiedList() {
        DirectoryScanTaskVO scan = directoryScanTaskService.createScanTask("/mnt/staging/comics");
        DirectoryScanTask st = directoryScanTaskMapper.selectById(scan.getId());
        assertThat(st.getManagementTaskId()).isNotNull();

        IPage<ManagementTaskResponse> page = managementTaskService.listTasks(
                1, 10, TaskType.DIRECTORY_SCAN, null, null, null, null);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(page.getRecords()).anyMatch(t -> t.getId().equals(st.getManagementTaskId()));
    }

    @Test
    @DisplayName("恢复任务创建后统一任务列表可见")
    void recoveryTask_appearsInUnifiedList() {
        var vo = recoveryTaskService.createRecoveryTask();
        RecoveryTask rt = recoveryTaskMapper.selectById(vo.getId());
        assertThat(rt.getManagementTaskId()).isNotNull();

        IPage<ManagementTaskResponse> page = managementTaskService.listTasks(
                1, 10, TaskType.RECOVERY, null, null, null, null);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(page.getRecords()).anyMatch(t -> t.getId().equals(rt.getManagementTaskId()));
    }

    // ======================== 过滤 ========================

    @Test
    @DisplayName("按 type/status/batch/target 过滤统一任务")
    void filter_byTypeStatusBatchTarget() {
        ImportTaskVO vo = importService.createImportTask(buildRequest("/mnt/import/过滤漫画F"), null);
        ImportTask it = importTaskMapper.selectById(vo.getId());

        IPage<ManagementTaskResponse> byType = managementTaskService.listTasks(
                1, 10, TaskType.IMPORT, null, null, null, null);
        assertThat(byType.getRecords()).isNotEmpty();
        assertThat(byType.getRecords()).allMatch(t -> t.getTaskType() == TaskType.IMPORT);

        IPage<ManagementTaskResponse> byStatus = managementTaskService.listTasks(
                1, 10, null, ManagementTaskStatus.QUEUED, null, null, null);
        assertThat(byStatus.getRecords()).isNotEmpty();
        assertThat(byStatus.getRecords()).allMatch(t -> t.getStatus() == ManagementTaskStatus.QUEUED);

        IPage<ManagementTaskResponse> byTarget = managementTaskService.listTasks(
                1, 10, null, null, null, null, it.getComicId());
        assertThat(byTarget.getRecords()).anyMatch(t -> t.getId().equals(it.getManagementTaskId()));
    }

    // ======================== 历史回填 ========================

    @Test
    @DisplayName("历史专表行回填后 management_task 数量一致")
    void backfill_legacyRows_createsMatchingManagementTasks() {
        Comic comic = insertReadyComic("回填漫画G");

        ImportTask it = new ImportTask();
        it.setComicId(comic.getId());
        it.setSourceType(SourceType.REGISTER);
        it.setStatus(ImportTaskStatus.SUCCESS);
        it.setProgress(100);
        importTaskMapper.insert(it);

        RecoveryTask rt = new RecoveryTask();
        rt.setStatus("SUCCEEDED");
        recoveryTaskMapper.insert(rt);

        ExportTask et = new ExportTask();
        et.setComicId(comic.getId());
        et.setStatus("FAILED");
        exportTaskMapper.insert(et);

        DirectoryScanTask st = new DirectoryScanTask();
        st.setStatus("SUCCESS");
        st.setDirectoryPath("/mnt/staging/legacy");
        directoryScanTaskMapper.insert(st);

        int created = backfillService.backfillAll();

        assertThat(created).isEqualTo(4);
        assertThat(importTaskMapper.selectById(it.getId()).getManagementTaskId()).isNotNull();
        assertThat(recoveryTaskMapper.selectById(rt.getId()).getManagementTaskId()).isNotNull();
        assertThat(exportTaskMapper.selectById(et.getId()).getManagementTaskId()).isNotNull();
        assertThat(directoryScanTaskMapper.selectById(st.getId()).getManagementTaskId()).isNotNull();

        long total = managementTaskMapper.selectCount(new LambdaQueryWrapper<ManagementTask>()
                .in(ManagementTask::getTaskType,
                        TaskType.IMPORT, TaskType.RECOVERY, TaskType.EXPORT, TaskType.DIRECTORY_SCAN));
        assertThat(total).isEqualTo(4);

        // 幂等：重复回填不产生新任务
        assertThat(backfillService.backfillAll()).isZero();
    }

    @Test
    @DisplayName("RECOVERY_REQUIRED 漫画不允许导出（导出链路一致）")
    void recoveryRequiredComic_cannotBeExported() {
        Comic comic = new Comic();
        comic.setTitle("待恢复漫画H");
        comic.setStatus(ComicStatus.RECOVERY_REQUIRED);
        comic.setStoragePolicy("MANAGED");
        comicMapper.insert(comic);

        assertThatThrownBy(() -> exportService.createExportTask(comic.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("漫画状态不允许导出");
    }

    // ======================== 辅助方法 ========================

    private Comic insertReadyComic(String title) {
        Comic comic = new Comic();
        comic.setTitle(title);
        comic.setStatus(ComicStatus.READY);
        comic.setStoragePolicy("MANAGED");
        comicMapper.insert(comic);
        return comic;
    }

    private ImportRequest buildRequest(String path) {
        ImportRequest request = new ImportRequest();
        request.setSourceType("REGISTER");
        request.setSourcePath(path);
        return request;
    }
}
