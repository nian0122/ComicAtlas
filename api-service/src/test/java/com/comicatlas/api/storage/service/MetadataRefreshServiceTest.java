package com.comicatlas.api.storage.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.api.admin.dto.RefreshMetadataResultDTO;
import com.comicatlas.api.common.scan.RecoveryEngine;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * MetadataRefreshService 单元测试：
 * 验证 CAS 锁、扫盘更新、视频元数据修复 MQ、metadata 刷新 MQ、finally 恢复 READY。
 */
class MetadataRefreshServiceTest {

    private final ComicMapper comicMapper = mock(ComicMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final MediaMapper mediaMapper = mock(MediaMapper.class);
    private final RecoveryEngine recoveryEngine = mock(RecoveryEngine.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final CatalogCacheInvalidator invalidator = mock(CatalogCacheInvalidator.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 单元测试无 Spring 上下文，需注册实体 TableInfo 以支持 LambdaUpdateWrapper 解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Comic.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chapter.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Media.class);
    }

    private MetadataRefreshService newService() {
        return new MetadataRefreshService(comicMapper, chapterMapper, mediaMapper,
                recoveryEngine, invalidator, rabbitTemplate, transactionTemplate);
    }

    private Comic readyComic() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setStatus(ComicStatus.READY);
        return comic;
    }

    @Test
    void refresh_漫画不存在时抛出业务异常() {
        when(comicMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> newService().refresh(1L));

        verify(comicMapper, never()).update(any(), any());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), (Object) any());
    }

    @Test
    void refresh_CAS失败时抛出冲突() {
        when(comicMapper.selectById(1L)).thenReturn(readyComic());
        // CAS 更新返回 0：并发下状态已非 READY
        when(comicMapper.update(any(), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> newService().refresh(1L));

        // 不应发送任何 MQ，也不应恢复状态；唯一一次 update 为 CAS 锁
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), (Object) any());
        ArgumentCaptor<LambdaUpdateWrapper<Comic>> casCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(comicMapper, times(1)).update(isNull(), casCaptor.capture());
        // CAS 锁必须限定目标漫画 ID + READY 状态（防整库被置为 REFRESHING）；
        // id 参数值类型随 MyBatis-Plus 版本在 Integer/Long 间波动，用数值比较
        assertThat(casCaptor.getValue().getSqlSegment()).contains("id");
        assertThat(casCaptor.getValue().getParamNameValuePairs().values())
                .anyMatch(v -> v instanceof Number n && n.intValue() == 1)
                .anyMatch(ComicStatus.READY::equals)
                .anyMatch(ComicStatus.REFRESHING::equals);
    }

    @Test
    void refresh_成功后发metadata刷新MQ并恢复READY() {
        when(comicMapper.selectById(1L)).thenReturn(readyComic());
        when(comicMapper.update(any(), any())).thenReturn(1);
        when(transactionTemplate.execute(any())).thenReturn(Map.of("catalogs", 0, "chapters", 0, "pages", 0));

        RefreshMetadataResultDTO result = newService().refresh(1L);

        // 结果组装正确
        assertThat(result.comicId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.chapters()).isZero();
        assertThat(result.pages()).isZero();

        // 视频元数据修复 MQ
        verify(rabbitTemplate).convertAndSend(eq("comic.image"), eq("video.metadata.fix.requested"),
                (Object) any());
        // metadata 刷新 MQ（导出）
        verify(rabbitTemplate).convertAndSend(eq("comic.export"), eq("metadata.refresh.requested"),
                (Object) any());
        // 目录缓存失效
        verify(invalidator).evict(1L);
        // CAS 锁 + finally 恢复 READY 各一次；
        // REFRESHING 仅出现在 CAS 锁的 set 目标中，可据此区分 finally 恢复
        ArgumentCaptor<LambdaUpdateWrapper<Comic>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(comicMapper, times(2)).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getAllValues()).anyMatch(w ->
                w.getSqlSegment().contains("id")
                        && w.getParamNameValuePairs().values().stream()
                                .anyMatch(v -> v instanceof Number n && n.intValue() == 1)
                        && w.getParamNameValuePairs().containsValue(ComicStatus.READY)
                        && w.getParamNameValuePairs().containsValue(ComicStatus.REFRESHING));
    }
}
