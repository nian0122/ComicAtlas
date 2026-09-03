package com.comicatlas.api.storage.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 漫画派生统计批量重算回归测试。 */
@ExtendWith(MockitoExtension.class)
class ComicStatsServiceTest {

    @Mock private MediaMapper mediaMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private ComicMapper comicMapper;
    @InjectMocks private ComicStatsService service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Chapter.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Comic.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Media.class);
    }

    @Test
    void refreshByComic_一次查询媒体并批量回写章节页数() {
        Chapter firstChapter = chapter(11L);
        Chapter secondChapter = chapter(12L);
        when(chapterMapper.selectList(any())).thenReturn(List.of(firstChapter, secondChapter));
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media(11L, 100L, 10L, HqStatus.READY, LqStatus.READY, MediaLifecycleStatus.READY),
                media(11L, 200L, 20L, HqStatus.DELETED, LqStatus.NOT_GENERATED, MediaLifecycleStatus.READY),
                media(12L, 300L, 30L, HqStatus.READY, LqStatus.READY, MediaLifecycleStatus.TRASHED)));

        service.refreshByComic(1L);

        verify(mediaMapper, times(1)).selectList(any());
        ArgumentCaptor<List<Chapter>> chaptersCaptor = ArgumentCaptor.forClass(List.class);
        verify(chapterMapper, times(1)).updatePageCountBatch(chaptersCaptor.capture());
        assertThat(chaptersCaptor.getValue()).extracting(Chapter::getPageCount).containsExactly(2, 0);
        verify(comicMapper, times(1)).update(isNull(), any());
    }

    private static Chapter chapter(Long chapterId) {
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setComicId(1L);
        return chapter;
    }

    private static Media media(Long chapterId, Long hqSize, Long lqSize, HqStatus hqStatus,
                               LqStatus lqStatus, MediaLifecycleStatus lifecycleStatus) {
        Media media = new Media();
        media.setChapterId(chapterId);
        media.setMediaType("IMAGE");
        media.setHqSize(hqSize);
        media.setLqSize(lqSize);
        media.setHqStatus(hqStatus);
        media.setLqStatus(lqStatus);
        media.setStatus(lifecycleStatus);
        return media;
    }
}
