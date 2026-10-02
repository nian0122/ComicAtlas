package com.comicatlas.api.storage.service;

import com.comicatlas.api.storage.adapter.ThumbnailCapacityScanner;
import com.comicatlas.api.storage.support.ThumbnailStorageIdentity;
import com.comicatlas.api.storage.config.ApiStorageProperties;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import com.comicatlas.api.storage.persistence.entity.ThumbnailCapacitySnapshot;
import com.comicatlas.api.storage.persistence.mapper.ThumbnailSnapshotMapper;
import com.comicatlas.common.constant.StorageRootKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单实例后台刷新协调器；持久化刷新版本，扫描期间发生的变更在下一轮处理。 */
@Service
@Slf4j
public class ThumbnailSnapshotService {
    private static final Duration CHANGE_DEBOUNCE = Duration.ofSeconds(5);
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(1);
    private final ThumbnailSnapshotMapper snapshotMapper;
    private final ThumbnailCapacityScanner scanner;
    private final ApiStorageProperties storageProperties;
    private final TaskExecutor executor;
    private final Duration refreshInterval;
    private final Duration scanTimeout;
    private final TransactionTemplate changeTransaction;
    private final AtomicBoolean scanSubmissionGuard = new AtomicBoolean();
    private final Clock clock = Clock.systemUTC();

    public ThumbnailSnapshotService(ThumbnailSnapshotMapper snapshotMapper, ThumbnailCapacityScanner scanner,
            ApiStorageProperties storageProperties, @Qualifier("thumbnailScanExecutor") TaskExecutor executor,
            @Value("${storage.statistics.refresh-interval:15m}") Duration refreshInterval,
            @Value("${storage.statistics.scan-timeout:2m}") Duration scanTimeout,
            PlatformTransactionManager transactionManager) {
        if (refreshInterval.isZero() || refreshInterval.isNegative() || scanTimeout.isZero() || scanTimeout.isNegative()) {
            throw new IllegalArgumentException("存储统计刷新间隔与扫描超时必须大于零");
        }
        this.snapshotMapper = snapshotMapper;
        this.scanner = scanner;
        this.storageProperties = storageProperties;
        this.executor = executor;
        this.refreshInterval = refreshInterval;
        this.scanTimeout = scanTimeout;
        this.changeTransaction = new TransactionTemplate(transactionManager);
        this.changeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        snapshotMapper.initialize(now());
        snapshotMapper.recoverInterruptedScan();
        ThumbnailCapacitySnapshot snapshot = snapshotMapper.selectSnapshot();
        if (snapshot.getScannedAt() != null && !rootFingerprint().equals(snapshot.getRootFingerprint())) {
            snapshotMapper.requestRefresh(now());
        }
        scheduleScan();
    }

    /** 定时器只投递任务，不占用通用调度线程扫描磁盘。 */
    @Scheduled(fixedDelayString = "${storage.statistics.poll-interval-ms:5000}")
    public void scheduleScan() {
        if (!scanSubmissionGuard.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    refreshIfNeeded();
                } catch (RuntimeException exception) {
                    log.warn("后台容量统计暂不可用，将在后续轮询重试", exception);
                } finally {
                    scanSubmissionGuard.set(false);
                }
            });
        } catch (RuntimeException exception) {
            scanSubmissionGuard.set(false);
            log.warn("容量扫描任务投递失败，将在后续轮询重试", exception);
        }
    }

    /** 手动刷新只登记请求；重复点击和运行中的刷新请求均合并。 */
    public SnapshotRefreshStatus requestRefresh() {
        ThumbnailCapacitySnapshot snapshot = snapshotMapper.selectSnapshot();
        if (snapshot == null) {
            snapshotMapper.initialize(now());
        } else if (snapshot.getRefreshStatus() != SnapshotRefreshStatus.RUNNING
                && snapshot.getRefreshStatus() != SnapshotRefreshStatus.PENDING) {
            snapshotMapper.requestRefresh(now());
        }
        scheduleScan();
        return SnapshotRefreshStatus.PENDING;
    }

    /** 文件变更在事务成功提交后登记，回滚不触发扫描。 */
    public void filesChangedAfterCommit() {
        Runnable request = () -> {
            try {
                changeTransaction.executeWithoutResult(status -> {
                    snapshotMapper.initialize(now());
                    snapshotMapper.requestRefresh(now());
                });
            } catch (RuntimeException exception) {
                log.warn("文件变更容量刷新登记失败，定期校准将补偿", exception);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    request.run();
                }
            });
        } else {
            request.run();
        }
    }

    private void refreshIfNeeded() {
        ThumbnailCapacitySnapshot snapshot = snapshotMapper.selectSnapshot();
        if (snapshot == null) {
            return;
        }
        LocalDateTime currentTime = now();
        if (snapshot.getRefreshStatus() == SnapshotRefreshStatus.FAILED && snapshot.getAttemptedAt() != null
                && snapshot.getAttemptedAt().plus(FAILURE_RETRY_DELAY).isAfter(currentTime)) {
            return;
        }
        if (snapshot.getRequestedVersion() <= snapshot.getCompletedVersion()) {
            if (snapshot.getScannedAt() != null && snapshot.getScannedAt().plus(refreshInterval).isAfter(currentTime)) {
                return;
            }
            snapshotMapper.requestRefresh(currentTime);
            snapshot = snapshotMapper.selectSnapshot();
        }
        if (snapshot.getScannedAt() != null && snapshot.getRequestedAt().plus(CHANGE_DEBOUNCE).isAfter(currentTime)) {
            return;
        }
        long version = snapshot.getRequestedVersion();
        if (snapshotMapper.startScan(version, currentTime) != 1) {
            return;
        }
        try {
            ThumbnailCapacityScanner.Capacity capacity = scanner.scan(
                    storageProperties.root(StorageRootKeys.THUMBS).getPath(), scanTimeout);
            snapshotMapper.completeScan(version, capacity.totalBytes(), capacity.fileCount(), now(), rootFingerprint());
            log.info("缩略图容量扫描完成: bytes={}, files={}", capacity.totalBytes(), capacity.fileCount());
        } catch (Exception exception) {
            snapshotMapper.failScan();
            log.warn("缩略图容量扫描失败，保留上次成功快照", exception);
        }
    }

    private String rootFingerprint() {
        return ThumbnailStorageIdentity.fingerprint(storageProperties.root(StorageRootKeys.THUMBS).getPath());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
