package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportPersistencePort;
import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort.CategorySnapshot;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 导入落库端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportPersistencePortAdapter implements ImportPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final CategoryMapper categoryMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicTagMapper comicTagMapper;
    private final TagMapper tagMapper;
    @Override public ImportTask findImportTask(Long taskId) { return importTaskMapper.selectById(taskId); }
    @Override public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }
    @Override public Comic findComicForUpdate(Long comicId) { return comicMapper.selectByIdForUpdate(comicId); }
    @Override public void updateComic(Comic comic) { comicMapper.updateById(comic); }
    @Override public void updateImportTask(ImportTask task) { importTaskMapper.updateById(task); }
    @Override public long countChapters(Long comicId) { return chapterMapper.countByComicId(comicId); }
    @Override public List<Long> findTagIds(Long comicId) { return comicTagMapper.selectTagIdsByComicId(comicId); }
    @Override public Tag findTag(String name, String type) { return tagMapper.selectByNameAndType(name, type); }
    @Override public void insertTag(Tag tag) { tagMapper.insert(tag); }
    @Override public void insertComicTag(ComicTag comicTag) { comicTagMapper.insert(comicTag); }
    @Override public List<CategorySnapshot> findCategories() {
        return categoryMapper.selectAllOrderedBySortOrder().stream()
                .map(category -> new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder()))
                .toList();
    }
    @Override public void insertCatalog(Catalog catalog) { catalogMapper.insert(catalog); }
    @Override public void updateCatalog(Catalog catalog) { catalogMapper.updateById(catalog); }
    @Override public void insertChapter(Chapter chapter) { chapterMapper.insert(chapter); }
    @Override public List<Chapter> findChapters(Long comicId) { return chapterMapper.selectByComicId(comicId); }
    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public void updateChapter(Chapter chapter) { chapterMapper.updateById(chapter); }
    @Override public int insertMediaBatch(List<Media> mediaItems) { return mediaMapper.insertImportBatch(mediaItems); }
    @Override public int markMediaFinalized(Long chapterId, String hqRelativePath) {
        return mediaMapper.markImportFinalizedByChapter(chapterId, hqRelativePath);
    }
    @Override public long countPendingMedia(List<Long> chapterIds, String hqStatus) {
        return mediaMapper.countByChapterIdsAndHqStatusNot(chapterIds, hqStatus);
    }
    @Override public List<Media> findAllMedia(List<Long> chapterIds) { return mediaMapper.selectAllByChapterIds(chapterIds); }
}
