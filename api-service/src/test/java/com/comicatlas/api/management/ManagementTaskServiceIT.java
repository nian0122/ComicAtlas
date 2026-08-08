package com.comicatlas.api.management;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * ManagementTaskService 集成测试（TDD）。
 *
 * <p>验证：
 * <ul>
 *   <li>幂等键：同键同 payload 返回原任务，不同 payload 409</li>
 *   <li>目标冲突锁：并发双 POST 只创建一个活跃 item</li>
 *   <li>分页过滤：type/status/batch/target 过滤</li>
 *   <li>Cancel：取消任务，QUEUED item → CANCELLED</li>
 *   <li>Retry：保持 taskId/itemId，递增 attempt，重置失败 item</li>
 *   <li>迟到结果：第一个终态结果胜出，后续结果记录后忽略</li>
 *   <li>聚合：任务根据 item 状态正确聚合 PARTIALLY_SUCCEEDED</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ManagementTaskService 集成测试")
class ManagementTaskServiceIT {

    private static boolean dockerAvailable;
    private static boolean useH2Fallback;

    static {
        dockerAvailable = checkDockerAvailable();
        useH2Fallback = !dockerAvailable;
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            useH2Fallback = true;
            registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.sql.init.mode", () -> "always");
        }
    }

    @Autowired
    private ManagementTaskService service;

    @Autowired
    private ManagementTaskMapper taskMapper;

    @Autowired
    private ManagementTaskItemMapper itemMapper;

    @AfterEach
    void tearDown() {
        if (itemMapper != null) { itemMapper.delete(new LambdaQueryWrapper<>()); }
        if (taskMapper != null) { taskMapper.delete(new LambdaQueryWrapper<>()); }
    }

    private static boolean checkDockerAvailable() {
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

    // ======================== 幂等测试 ========================

    @Nested
    @DisplayName("Idempotency-Key 幂等")
    class IdempotencyTests {

        @Test
        @DisplayName("同键同 payload 返回原任务")
        void sameKeySamePayload_returnsExistingTask() {
            CreateManagementTaskRequest request = createImportRequest("IMPORT_001");
            String payload = request.toString();

            ManagementTaskResponse first = service.createTask(request, "idem-key-1", payload);
            ManagementTaskResponse second = service.createTask(request, "idem-key-1", payload);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(second.getTaskType()).isEqualTo(TaskType.IMPORT);
        }

        @Test
        @DisplayName("同键不同 payload 抛出 409 Conflict")
        void sameKeyDifferentPayload_throwsConflict() {
            CreateManagementTaskRequest req1 = createImportRequest("IMPORT_001");
            CreateManagementTaskRequest req2 = createImportRequest("IMPORT_002");

            service.createTask(req1, "idem-key-2", req1.toString());

            assertThatThrownBy(() -> service.createTask(req2, "idem-key-2", req2.toString()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("payload 不匹配");
        }

        @Test
        @DisplayName("无 Idempotency-Key 时每次创建新任务")
        void noIdempotencyKey_createsNewTaskEachTime() {
            CreateManagementTaskRequest request = createImportRequest("IMPORT_003");

            ManagementTaskResponse first = service.createTask(request, null, null);
            ManagementTaskResponse second = service.createTask(request, null, null);

            assertThat(second.getId()).isNotEqualTo(first.getId());
        }
    }

    // ======================== 目标冲突锁测试 ========================

    @Nested
    @DisplayName("目标冲突锁")
    class TargetLockTests {

        @Test
        @DisplayName("同一目标已有活跃 item 时创建新任务 → 409")
        void activeItemConflict_throwsConflict() {
            // 创建第一个任务占用锁
            CreateManagementTaskRequest req1 = buildRequestWithTarget("COMIC", 100L, TaskType.IMPORT);
            service.createTask(req1, null, null);

            // 尝试创建第二个任务，同目标同操作
            CreateManagementTaskRequest req2 = buildRequestWithTarget("COMIC", 100L, TaskType.IMPORT);

            assertThatThrownBy(() -> service.createTask(req2, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已有活跃任务项");
        }

        @Test
        @DisplayName("目标 item 完成后释放锁，新任务可创建")
        void completedItemReleasesLock() {
            CreateManagementTaskRequest req1 = buildRequestWithTarget("COMIC", 200L, TaskType.IMPORT);
            ManagementTaskResponse task1 = service.createTask(req1, null, null);

            // 获取 item 并标记为 SUCCEEDED
            List<ManagementTaskItemResponse> items = service.getTaskItems(task1.getId());
            assertThat(items).hasSize(1);
            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);

            // 现在可以创建新任务
            CreateManagementTaskRequest req2 = buildRequestWithTarget("COMIC", 200L, TaskType.IMPORT);
            ManagementTaskResponse task2 = service.createTask(req2, null, null);
            assertThat(task2.getId()).isNotEqualTo(task1.getId());
        }

        @Test
        @DisplayName("不同目标 ID 不冲突")
        void differentTarget_noConflict() {
            service.createTask(buildRequestWithTarget("COMIC", 300L, TaskType.IMPORT), null, null);
            ManagementTaskResponse task2 = service.createTask(
                    buildRequestWithTarget("COMIC", 301L, TaskType.IMPORT), null, null);
            assertThat(task2).isNotNull();
        }

        @Test
        @DisplayName("不同操作类型不冲突")
        void differentOperationType_noConflict() {
            service.createTask(buildRequestWithTarget("COMIC", 400L, TaskType.IMPORT), null, null);
            ManagementTaskResponse task2 = service.createTask(
                    buildRequestWithTarget("COMIC", 400L, TaskType.EXPORT), null, null);
            assertThat(task2).isNotNull();
        }
    }

    // ======================== 并发锁测试 ========================

    @Nested
    @DisplayName("并发目标锁")
    class ConcurrentLockTests {

        @Test
        @DisplayName("并发双 POST 同目标只创建一个活跃 item")
        void concurrentCreate_sameTarget_onlyOneActiveItem() throws Exception {
            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);

            CreateManagementTaskRequest request = buildRequestWithTarget("COMIC", 500L, TaskType.IMPORT);
            ConcurrentLinkedQueue<Long> taskIds = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await(); // 同时出发
                        ManagementTaskResponse resp = service.createTask(request, null, null);
                        taskIds.add(resp.getId());
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // 应该只有一个成功创建，另一个抛冲突异常
            assertThat(taskIds).hasSize(1);
            assertThat(errors).hasSize(1);
            assertThat(errors.peek())
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已有活跃任务项");

            // 验证 DB 中只有一个活跃 item
            Long createdTaskId = taskIds.peek();
            List<ManagementTaskItem> items = itemMapper.selectList(
                    new LambdaQueryWrapper<ManagementTaskItem>()
                            .eq(ManagementTaskItem::getTaskId, createdTaskId));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getLockKey()).isNotNull();
        }
    }

    // ======================== 分页过滤测试 ========================

    @Nested
    @DisplayName("分页过滤")
    class PaginationFilterTests {

        @BeforeEach
        void setUpData() {
            // 创建不同 type/status 的任务用于测试过滤
            service.createTask(createImportRequest("BATCH-001"), "ik-page-1", "p1");
            service.createTask(createExportRequest("BATCH-002"), "ik-page-2", "p2");
            service.createTask(createRecoveryRequest(), "ik-page-3", "p3");
        }

        @Test
        @DisplayName("按 taskType 过滤")
        void filterByType() {
            IPage<ManagementTaskResponse> page = service.listTasks(
                    1, 10, TaskType.IMPORT, null, null, null, null);
            assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
            assertThat(page.getRecords()).allMatch(t -> t.getTaskType() == TaskType.IMPORT);
        }

        @Test
        @DisplayName("按 status 过滤")
        void filterByStatus() {
            IPage<ManagementTaskResponse> page = service.listTasks(
                    1, 10, null, ManagementTaskStatus.QUEUED, null, null, null);
            assertThat(page.getRecords()).allMatch(t -> t.getStatus() == ManagementTaskStatus.QUEUED);
        }

        @Test
        @DisplayName("按 batchId 过滤")
        void filterByBatchId() {
            IPage<ManagementTaskResponse> page = service.listTasks(
                    1, 10, null, null, "BATCH-001", null, null);
            assertThat(page.getTotal()).isEqualTo(1);
            assertThat(page.getRecords().get(0).getBatchId()).isEqualTo("BATCH-001");
        }

        @Test
        @DisplayName("组合过滤 type + status")
        void filterByTypeAndStatus() {
            IPage<ManagementTaskResponse> page = service.listTasks(
                    1, 10, TaskType.EXPORT, ManagementTaskStatus.QUEUED, null, null, null);
            assertThat(page.getRecords())
                .allMatch(t -> t.getTaskType() == TaskType.EXPORT
                        && t.getStatus() == ManagementTaskStatus.QUEUED);
        }

        @Test
        @DisplayName("按 targetId 过滤（通过 item 反查）")
        void filterByTargetId() {
            // 创建一个带 COMIC:600 target 的任务
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 600L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);

            IPage<ManagementTaskResponse> page = service.listTasks(
                    1, 10, null, null, null, null, 600L);
            assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
            assertThat(page.getRecords()).anyMatch(t -> t.getId().equals(task.getId()));
        }
    }

    // ======================== 详情和 items ========================

    @Nested
    @DisplayName("详情和逐目标项")
    class DetailAndItemsTests {

        @Test
        @DisplayName("getTask 返回任务详情")
        void getTask_returnsDetail() {
            CreateManagementTaskRequest req = createImportRequest("DETAIL-001");
            ManagementTaskResponse created = service.createTask(req, null, null);

            ManagementTaskResponse detail = service.getTask(created.getId());
            assertThat(detail.getId()).isEqualTo(created.getId());
            assertThat(detail.getTaskType()).isEqualTo(TaskType.IMPORT);
            assertThat(detail.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
        }

        @Test
        @DisplayName("getTaskItems 返回逐目标项列表")
        void getTaskItems_returnsItemList() {
            CreateManagementTaskRequest req = buildMultiTargetRequest(
                    new String[]{"COMIC", "COMIC", "COMIC"},
                    new Long[]{701L, 702L, 703L},
                    TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);

            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());
            assertThat(items).hasSize(3);
            assertThat(items).extracting(ManagementTaskItemResponse::getTargetId)
                    .containsExactlyInAnyOrder(701L, 702L, 703L);
        }

        @Test
        @DisplayName("getTask 不存在时 404")
        void getTask_notFound_throws404() {
            assertThatThrownBy(() -> service.getTask(99999L))
                .isInstanceOf(com.comicatlas.api.common.exception.BusinessException.class)
                .hasMessageContaining("任务不存在");
        }
    }

    // ======================== Cancel 测试 ========================

    @Nested
    @DisplayName("取消任务")
    class CancelTests {

        @Test
        @DisplayName("QUEUED 任务可取消")
        void queuedTask_canBeCancelled() {
            CreateManagementTaskRequest req = buildMultiTargetRequest(
                    new String[]{"COMIC", "COMIC"},
                    new Long[]{801L, 802L},
                    TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);

            ManagementTaskResponse cancelled = service.cancelTask(task.getId());
            assertThat(cancelled.getStatus()).isIn(
                    ManagementTaskStatus.CANCELLING, ManagementTaskStatus.CANCELLED);

            // item 应被标记为 CANCELLED
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());
            assertThat(items).allMatch(i -> i.getStatus() == ManagementTaskStatus.CANCELLED);
        }

        @Test
        @DisplayName("终态任务无法取消")
        void terminalTask_cannotBeCancelled() {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 803L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());
            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);

            assertThatThrownBy(() -> service.cancelTask(task.getId()))
                .isInstanceOf(com.comicatlas.api.common.exception.BusinessException.class)
                .hasMessageContaining("已处于终态");
        }
    }

    // ======================== Retry 测试 ========================

    @Nested
    @DisplayName("重试任务")
    class RetryTests {

        @Test
        @DisplayName("FAILED 任务重试：保持 taskId/itemId，递增 attempt")
        void failedTask_retry_incrementsAttempt() {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 901L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);

            // 标记 item 为 FAILED
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());
            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.FAILED,
                    "测试失败", null, null);

            ManagementTaskResponse failedTask = service.getTask(task.getId());
            assertThat(failedTask.getStatus()).isEqualTo(ManagementTaskStatus.FAILED);

            // 重试
            ManagementTaskResponse retried = service.retryTask(task.getId());
            assertThat(retried.getAttempt()).isEqualTo(2);
            assertThat(retried.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
            assertThat(retried.getFailureCount()).isEqualTo(0);

            // item 应重置
            List<ManagementTaskItemResponse> retriedItems = service.getTaskItems(task.getId());
            assertThat(retriedItems.get(0).getAttempt()).isEqualTo(2);
            assertThat(retriedItems.get(0).getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
            assertThat(retriedItems.get(0).getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("PARTIALLY_SUCCEEDED 任务重试：只重置失败 item，成功 item 保持不变")
        void partiallySucceeded_retry_resetsOnlyFailed() {
            CreateManagementTaskRequest req = buildMultiTargetRequest(
                    new String[]{"COMIC", "COMIC"},
                    new Long[]{902L, 903L},
                    TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());

            // item[0] → SUCCEEDED, item[1] → FAILED
            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);
            service.updateItemStatus(items.get(1).getId(), ManagementTaskStatus.FAILED,
                    "失败", null, null);

            ManagementTaskResponse original = service.getTask(task.getId());
            assertThat(original.getStatus()).isEqualTo(ManagementTaskStatus.PARTIALLY_SUCCEEDED);

            // 重试
            ManagementTaskResponse retried = service.retryTask(task.getId());

            List<ManagementTaskItemResponse> retriedItems = service.getTaskItems(task.getId());
            // item[0] 保持 SUCCEEDED（attempt 不递增）
            ManagementTaskItemResponse succeededItem = retriedItems.stream()
                    .filter(i -> i.getTargetId() == 902L).findFirst().orElseThrow();
            assertThat(succeededItem.getStatus()).isEqualTo(ManagementTaskStatus.SUCCEEDED);

            // item[1] 重置为 QUEUED（attempt 递增到 2）
            ManagementTaskItemResponse resetItem = retriedItems.stream()
                    .filter(i -> i.getTargetId() == 903L).findFirst().orElseThrow();
            assertThat(resetItem.getStatus()).isEqualTo(ManagementTaskStatus.QUEUED);
            assertThat(resetItem.getAttempt()).isEqualTo(2);
        }

        @Test
        @DisplayName("非终态任务不可重试")
        void nonTerminalTask_cannotRetry() {
            CreateManagementTaskRequest req = createImportRequest("BATCH-RT");
            ManagementTaskResponse task = service.createTask(req, null, null);

            assertThatThrownBy(() -> service.retryTask(task.getId()))
                .isInstanceOf(com.comicatlas.api.common.exception.BusinessException.class)
                .hasMessageContaining("仅终态可重试");
        }
    }

    // ======================== Item 状态更新 + 迟到结果 ========================

    @Nested
    @DisplayName("Item 状态更新与迟到结果")
    class ItemStatusUpdateTests {

        @Test
        @DisplayName("更新 item 为 SUCCEEDED → 主任务聚合 SUCCEEDED")
        void itemSucceeded_aggregatesToSucceeded() {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 1001L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());

            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);

            ManagementTaskResponse updated = service.getTask(task.getId());
            assertThat(updated.getStatus()).isEqualTo(ManagementTaskStatus.SUCCEEDED);
            assertThat(updated.getSuccessCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("迟到结果：item 已终态，后续状态更新被忽略")
        void lateResult_ignored() {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 1002L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());

            // 先标记 SUCCEEDED
            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);

            // 迟到 FAILED 结果 → 被忽略（item 保持 SUCCEEDED）
            ManagementTaskItemResponse lateResult = service.updateItemStatus(
                    items.get(0).getId(), ManagementTaskStatus.FAILED,
                    "迟到错误", null, null);

            assertThat(lateResult.getStatus()).isEqualTo(ManagementTaskStatus.SUCCEEDED);
            assertThat(lateResult.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("多 item 部分成功 → 聚合 PARTIALLY_SUCCEEDED")
        void partiallySucceeded_aggregation() {
            CreateManagementTaskRequest req = buildMultiTargetRequest(
                    new String[]{"COMIC", "COMIC", "COMIC"},
                    new Long[]{1003L, 1004L, 1005L},
                    TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());

            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);
            service.updateItemStatus(items.get(1).getId(), ManagementTaskStatus.FAILED,
                    "error", null, null);
            service.updateItemStatus(items.get(2).getId(), ManagementTaskStatus.SUCCEEDED,
                    null, null, null);

            ManagementTaskResponse aggregated = service.getTask(task.getId());
            assertThat(aggregated.getStatus()).isEqualTo(ManagementTaskStatus.PARTIALLY_SUCCEEDED);
            assertThat(aggregated.getSuccessCount()).isEqualTo(2);
            assertThat(aggregated.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("更新 item 为 RUNNING → 主任务变为 RUNNING")
        void itemRunning_taskBecomesRunning() {
            CreateManagementTaskRequest req = buildRequestWithTarget("COMIC", 1006L, TaskType.IMPORT);
            ManagementTaskResponse task = service.createTask(req, null, null);
            List<ManagementTaskItemResponse> items = service.getTaskItems(task.getId());

            service.updateItemStatus(items.get(0).getId(), ManagementTaskStatus.RUNNING,
                    null, null, null);

            ManagementTaskResponse updated = service.getTask(task.getId());
            assertThat(updated.getStatus()).isEqualTo(ManagementTaskStatus.RUNNING);
        }
    }

    // ======================== 辅助方法 ========================

    private CreateManagementTaskRequest createImportRequest(String batchId) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(TaskType.IMPORT);
        req.setOperation("导入漫画");
        req.setTargetType("COMIC");
        req.setBatchId(batchId);
        req.setTargets(List.of());
        return req;
    }

    private CreateManagementTaskRequest createExportRequest(String batchId) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(TaskType.EXPORT);
        req.setOperation("导出漫画");
        req.setTargetType("COMIC");
        req.setBatchId(batchId);
        req.setTargets(List.of());
        return req;
    }

    private CreateManagementTaskRequest createRecoveryRequest() {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(TaskType.RECOVERY);
        req.setOperation("存储恢复");
        req.setTargetType("SYSTEM");
        req.setTargets(List.of());
        return req;
    }

    private CreateManagementTaskRequest buildRequestWithTarget(String targetType, Long targetId,
                                                                TaskType operationType) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operationType);
        req.setOperation("测试操作");
        req.setTargetType(targetType);

        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(targetType);
        target.setTargetId(targetId);
        target.setOperationType(operationType);

        req.setTargets(List.of(target));
        return req;
    }

    private CreateManagementTaskRequest buildMultiTargetRequest(String[] targetTypes,
                                                                  Long[] targetIds,
                                                                  TaskType operationType) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operationType);
        req.setOperation("批量测试操作");
        req.setTargetType(targetTypes[0]);

        List<CreateManagementTaskRequest.TaskTarget> targets = new java.util.ArrayList<>();
        for (int i = 0; i < targetTypes.length; i++) {
            CreateManagementTaskRequest.TaskTarget t = new CreateManagementTaskRequest.TaskTarget();
            t.setTargetType(targetTypes[i]);
            t.setTargetId(targetIds[i]);
            t.setOperationType(operationType);
            targets.add(t);
        }
        req.setTargets(targets);
        return req;
    }
}
