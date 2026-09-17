package com.comicatlas.api.media.application.service.impl;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.api.media.application.port.in.HqMediaRegistrationService;
import com.comicatlas.api.media.application.port.out.HqMediaRegistrationPersistencePort;
import com.comicatlas.api.media.application.service.HqMediaRegistrationPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** HQ 媒体登记应用服务：查询快照上下文、提交规划后的媒体实体。 */
@Slf4j
@Service
public class HqMediaRegistrationServiceImpl implements HqMediaRegistrationService {
    private final HqMediaRegistrationPersistencePort persistencePort;
    private final HqMediaRegistrationPlanner registrationPlanner;

    @Autowired
    public HqMediaRegistrationServiceImpl(HqMediaRegistrationPersistencePort persistencePort,
            HqMediaRegistrationPlanner registrationPlanner) {
        this.persistencePort = persistencePort;
        this.registrationPlanner = registrationPlanner;
    }

    /** 兼容历史单元测试构造器，登记规则使用无状态规划器。 */
    public HqMediaRegistrationServiceImpl(HqMediaRegistrationPersistencePort persistencePort) {
        this(persistencePort, new HqMediaRegistrationPlanner());
    }

    @Transactional
    public HqMediaRegistrationResult registerValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot) {
        List<ChapterSnapshot> chapterSnapshots = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        List<HqMediaRegistrationPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(snapshot.comicId());
        List<Long> chapterIds = chapterSnapshots.stream().map(ChapterSnapshot::chapterId).toList();
        List<HqMediaRegistrationPersistencePort.MediaSnapshot> databaseMedia = chapterIds.isEmpty()
                ? List.of() : persistencePort.findMediaByChapters(chapterIds);
        HqMediaRegistrationPlanner.RegistrationPlan plan = registrationPlanner.plan(
                snapshot, chapters, databaseMedia);
        for (List<HqMediaRegistrationPersistencePort.MediaRegistrationCommand> batch : partition(plan.media(), 500)) {
            persistencePort.insertMediaBatch(batch);
        }
        log.info("HQ 媒体登记完成: comicId={}, inserted={}, skippedExisting={}, skippedInvalid={}",
                snapshot.comicId(), plan.media().size(), plan.skippedExisting(), plan.skippedInvalid());
        return new HqMediaRegistrationResult(snapshot.comicId(), plan.media().size(),
                plan.skippedExisting(), plan.skippedInvalid());
    }

    private static <T> List<List<T>> partition(List<T> source, int batchSize) {
        List<List<T>> batches = new java.util.ArrayList<>((source.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < source.size(); start += batchSize) {
            batches.add(source.subList(start, Math.min(start + batchSize, source.size())));
        }
        return batches;
    }

}
