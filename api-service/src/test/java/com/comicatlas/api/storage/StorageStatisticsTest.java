package com.comicatlas.api.storage;

import com.comicatlas.api.storage.adapter.ThumbnailCapacityScanner;
import com.comicatlas.api.storage.config.ApiStorageProperties;
import com.comicatlas.api.storage.support.ThumbnailStorageIdentity;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import com.comicatlas.api.storage.persistence.mapper.StorageMapper;
import com.comicatlas.api.storage.persistence.mapper.ThumbnailSnapshotMapper;
import com.comicatlas.api.storage.service.StorageStatisticsService;
import com.comicatlas.api.storage.service.ThumbnailSnapshotService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真正执行汇总、列表及快照 SQL，验证后台失败与并发变更不会损坏上次成功值。 */
class StorageStatisticsTest {
    private StorageMapper storageMapper;
    private ThumbnailSnapshotMapper snapshotMapper;
    private DriverManagerDataSource dataSource;
    @TempDir private Path thumbnailRoot;

    @BeforeEach
    void createIndependentDatabase() throws Exception {
        dataSource = createDataSource();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            if (dataSource.getUrl().startsWith("jdbc:h2:")) {
                statement.execute("DROP ALL OBJECTS");
            } else {
                for (String table : List.of("storage_capacity_snapshot", "comic_tag", "tag", "category", "page", "chapter", "comic")) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
            statement.execute("CREATE TABLE comic (id BIGINT PRIMARY KEY, title VARCHAR(100), status VARCHAR(30), deleted_at TIMESTAMP, category_id BIGINT)");
            statement.execute("CREATE TABLE chapter (id BIGINT PRIMARY KEY, comic_id BIGINT, status VARCHAR(30), chapter_no VARCHAR(30), title VARCHAR(100), global_order INT)");
            statement.execute("CREATE TABLE page (id BIGINT PRIMARY KEY, chapter_id BIGINT, status VARCHAR(30), media_type VARCHAR(30), hq_status VARCHAR(30), lq_status VARCHAR(30), hq_size BIGINT, lq_size BIGINT, transcode_status VARCHAR(30))");
            statement.execute("CREATE TABLE category (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE tag (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE comic_tag (comic_id BIGINT, tag_id BIGINT)");
            statement.execute(Files.readString(Path.of("src/main/resources/db/flyway/V34__create_storage_capacity_snapshot.sql")));
            statement.execute("INSERT INTO comic VALUES (1, '活动漫画', 'READY', NULL, NULL), (2, '回收漫画', 'TRASHED', NULL, NULL), (3, '空漫画', 'DRAFT', NULL, NULL), (4, '导入漫画', 'IMPORTING', NULL, NULL)");
            statement.execute("INSERT INTO chapter VALUES (1, 1, 'READY', '1', '活动章', 2), (2, 1, 'TRASHED', '2', '回收章', 1), (3, 2, 'READY', '1', '整本回收', 1), (4, 4, 'DRAFT', '1', '暂存章', 1)");
            statement.execute("""
                    INSERT INTO page VALUES
                        (1, 1, 'READY', 'IMAGE', 'READY', 'READY', 100, 10, NULL),
                        (2, 1, 'READY', 'VIDEO', 'READY', 'READY', 200, 999, 'DONE'),
                        (3, 1, 'TRASHED', 'IMAGE', 'READY', 'READY', 1000, 1000, NULL),
                        (4, 2, 'READY', 'IMAGE', 'READY', 'READY', 2000, 2000, NULL),
                        (5, 3, 'READY', 'IMAGE', 'READY', 'READY', 3000, 3000, NULL),
                        (6, 4, 'STAGING', 'IMAGE', 'PENDING', 'NOT_GENERATED', 100, 0, NULL),
                        (7, 1, 'READY', 'IMAGE', 'MISSING', 'FAILED', 900, 900, NULL)
                    """);
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        try (var mapperSource = Files.newInputStream(Path.of("src/main/resources/mapper/StorageMapper.xml"))) {
            new XMLMapperBuilder(mapperSource, configuration, "StorageMapper.xml", configuration.getSqlFragments()).parse();
        }
        configuration.addMapper(ThumbnailSnapshotMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        storageMapper = session.getMapper(StorageMapper.class);
        snapshotMapper = session.getMapper(ThumbnailSnapshotMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void summaryMatchesListAndExcludesRecycledMediaAndVideoLq() {
        StorageStatsDTO summary = storageMapper.selectLibraryCapacity();
        var rows = storageMapper.selectComicStorageList(new ComicStorageQuery(), 0, 20);
        assertEquals(3, summary.getComicCount());
        assertEquals(300, summary.getHqBytes());
        assertEquals(10, summary.getLqBytes());
        assertEquals(summary.getHqBytes(), rows.stream().mapToLong(row -> row.getHqSize()).sum());
        assertEquals(summary.getLqBytes(), rows.stream().mapToLong(row -> row.getLqSize()).sum());
        assertEquals(rows.size(), storageMapper.countComicStorageList(new ComicStorageQuery()));
        assertEquals(1, storageMapper.selectChapterStorageList(1L).size());
        assertNull(storageMapper.selectComicStorageById(2L));
    }

    @Test
    void queryWithoutFirstSnapshotReturnsIncompleteTotalWithoutScanning() {
        StorageStatsDTO statistics = new StorageStatisticsService(storageMapper, snapshotMapper, storageProperties()).getStatistics();
        assertFalse(statistics.isHasSnapshot());
        assertNull(statistics.getTotalBytes());
        assertEquals(SnapshotRefreshStatus.PENDING, statistics.getRefreshStatus());
        assertEquals(300, statistics.getHqBytes());
    }

    @Test
    void queryRetainsSuccessfulSnapshotEvenWhenLatestRefreshFailed() {
        snapshotMapper.initialize(LocalDateTime.now(Clock.systemUTC()));
        snapshotMapper.completeScan(1, 50, 2, LocalDateTime.now(Clock.systemUTC()), ThumbnailStorageIdentity.fingerprint(thumbnailRoot));
        snapshotMapper.failScan();
        StorageStatsDTO statistics = new StorageStatisticsService(storageMapper, snapshotMapper, storageProperties()).getStatistics();
        assertEquals(360L, statistics.getTotalBytes());
        assertTrue(statistics.isHasSnapshot());
        assertEquals(SnapshotRefreshStatus.FAILED, statistics.getRefreshStatus());
        assertEquals(2, statistics.getThumbFileCount());
    }

    @Test
    void manualRequestIsAsyncAndDuplicateRequestsAreMerged() throws Exception {
        ThumbnailCapacityScanner scanner = mock(ThumbnailCapacityScanner.class);
        when(scanner.scan(any(), any())).thenReturn(new ThumbnailCapacityScanner.Capacity(80, 1));
        List<Runnable> queuedTasks = new ArrayList<>();
        ThumbnailSnapshotService service = coordinator(scanner, queuedTasks::add);
        service.requestRefresh();
        service.requestRefresh();
        assertEquals(1, queuedTasks.size());
        verifyNoInteractions(scanner);
        queuedTasks.removeFirst().run();
        assertEquals(80, snapshotMapper.selectSnapshot().getTotalBytes());
        assertEquals(SnapshotRefreshStatus.READY, snapshotMapper.selectSnapshot().getRefreshStatus());
    }

    @Test
    void changesDuringScanRemainPendingForNextScan() throws Exception {
        snapshotMapper.initialize(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        ThumbnailCapacityScanner scanner = mock(ThumbnailCapacityScanner.class);
        when(scanner.scan(any(), any())).thenAnswer(invocation -> {
            snapshotMapper.requestRefresh(LocalDateTime.now(Clock.systemUTC()));
            return new ThumbnailCapacityScanner.Capacity(80, 1);
        });
        coordinator(scanner, Runnable::run).scheduleScan();
        var snapshot = snapshotMapper.selectSnapshot();
        assertEquals(2, snapshot.getRequestedVersion());
        assertEquals(1, snapshot.getCompletedVersion());
        assertEquals(SnapshotRefreshStatus.PENDING, snapshot.getRefreshStatus());
    }

    @Test
    void scanFailurePreservesSnapshotAndDoesNotImmediatelyRetry() throws Exception {
        snapshotMapper.initialize(LocalDateTime.now(Clock.systemUTC()).minusMinutes(2));
        snapshotMapper.completeScan(1, 50, 2, LocalDateTime.now(Clock.systemUTC()).minusHours(1), ThumbnailStorageIdentity.fingerprint(thumbnailRoot));
        snapshotMapper.requestRefresh(LocalDateTime.now(Clock.systemUTC()).minusMinutes(2));
        ThumbnailCapacityScanner scanner = mock(ThumbnailCapacityScanner.class);
        when(scanner.scan(any(), any())).thenThrow(new IOException("模拟磁盘不可用"));
        ThumbnailSnapshotService service = coordinator(scanner, Runnable::run);
        service.scheduleScan();
        service.scheduleScan();
        verify(scanner, times(1)).scan(any(), any());
        assertEquals(50, snapshotMapper.selectSnapshot().getTotalBytes());
        assertEquals(SnapshotRefreshStatus.FAILED, snapshotMapper.selectSnapshot().getRefreshStatus());
    }

    @Test
    void expiredSnapshotRefreshesInBackgroundAndInterruptedScanRecovers() throws Exception {
        snapshotMapper.initialize(LocalDateTime.now(Clock.systemUTC()).minusHours(1));
        snapshotMapper.completeScan(1, 50, 1, LocalDateTime.now(Clock.systemUTC()).minusHours(1), ThumbnailStorageIdentity.fingerprint(thumbnailRoot));
        ThumbnailCapacityScanner scanner = mock(ThumbnailCapacityScanner.class);
        when(scanner.scan(any(), any())).thenReturn(new ThumbnailCapacityScanner.Capacity(70, 2));
        List<Runnable> tasks = new ArrayList<>();
        ThumbnailSnapshotService service = coordinator(scanner, tasks::add);
        service.scheduleScan();
        tasks.removeFirst().run();
        // 定期校准先登记一个新版本，防抖窗口内不执行扫描。
        assertEquals(SnapshotRefreshStatus.PENDING, snapshotMapper.selectSnapshot().getRefreshStatus());
        snapshotMapper.startScan(2, LocalDateTime.now(Clock.systemUTC()));
        service.initialize();
        assertEquals(SnapshotRefreshStatus.PENDING, snapshotMapper.selectSnapshot().getRefreshStatus());
    }

    @Test
    void rolledBackFileChangesDoNotRegisterRefresh() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            coordinator(mock(ThumbnailCapacityScanner.class), Runnable::run).filesChangedAfterCommit();
            status.setRollbackOnly();
        });
        assertNull(snapshotMapper.selectSnapshot());
    }

    @Test
    void committedFileChangesPersistNewRefreshInIndependentTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            coordinator(mock(ThumbnailCapacityScanner.class), Runnable::run).filesChangedAfterCommit();
            assertNull(snapshotMapper.selectSnapshot());
        });
        assertEquals(2, snapshotMapper.selectSnapshot().getRequestedVersion());
    }

    @Test
    void scannerCountsNestedFilesAndRejectsMissingStorageRoot() throws Exception {
        Files.createDirectories(thumbnailRoot.resolve("1"));
        Files.write(thumbnailRoot.resolve("1/cover.webp"), new byte[8]);
        Files.write(thumbnailRoot.resolve("extra.webp"), new byte[4]);
        ThumbnailCapacityScanner scanner = new ThumbnailCapacityScanner();
        assertEquals(new ThumbnailCapacityScanner.Capacity(12, 2), scanner.scan(thumbnailRoot, Duration.ofMinutes(1)));
        assertThrows(IOException.class, () -> scanner.scan(thumbnailRoot.resolve("missing"), Duration.ofMinutes(1)));
        assertThrows(IOException.class, () -> scanner.scan(thumbnailRoot, Duration.ZERO));
    }

    @Test
    void jsonPreservesSnapshotAvailabilityContractWithoutDuplicateFlag() throws Exception {
        StorageStatsDTO statistics = new StorageStatisticsService(storageMapper, snapshotMapper, storageProperties()).getStatistics();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(statistics);
        assertTrue(json.has("snapshotAvailable"));
        assertFalse(json.has("hasSnapshot"));
        assertTrue(json.get("totalBytes").isNull());
    }

    @Test
    void changingConfiguredRootDoesNotDisplayCapacityFromPreviousRoot() {
        snapshotMapper.initialize(LocalDateTime.now(Clock.systemUTC()));
        snapshotMapper.completeScan(1, 99, 1, LocalDateTime.now(Clock.systemUTC()), "previous-root");
        StorageStatsDTO statistics = new StorageStatisticsService(storageMapper, snapshotMapper, storageProperties()).getStatistics();
        assertFalse(statistics.isHasSnapshot());
        assertNull(statistics.getTotalBytes());
        List<Runnable> tasks = new ArrayList<>();
        coordinator(mock(ThumbnailCapacityScanner.class), tasks::add).initialize();
        assertEquals(SnapshotRefreshStatus.PENDING, snapshotMapper.selectSnapshot().getRefreshStatus());
    }

    protected DriverManagerDataSource createDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:statistics;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private ApiStorageProperties storageProperties() {
        ApiStorageProperties properties = new ApiStorageProperties();
        ApiStorageRoot root = new ApiStorageRoot();
        root.setPath(thumbnailRoot);
        properties.setRoots(Map.of("THUMBS", root));
        return properties;
    }

    private ThumbnailSnapshotService coordinator(ThumbnailCapacityScanner scanner, TaskExecutor executor) {
        return new ThumbnailSnapshotService(snapshotMapper, scanner, storageProperties(), executor,
                Duration.ofMinutes(15), Duration.ofMinutes(2), new DataSourceTransactionManager(dataSource));
    }
}
