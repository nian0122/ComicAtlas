package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportPersistencePort;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.CatalogModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ChapterModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ComicModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ComicTagModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ImportTaskModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.MediaModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.TagModel;
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
import org.springframework.beans.BeanUtils;
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
    @Override public ImportTaskModel findImportTask(Long taskId) {
        return copy(importTaskMapper.selectById(taskId), ImportTaskModel.class);
    }
    @Override public ComicModel findComic(Long comicId) {
        return copy(comicMapper.selectById(comicId), ComicModel.class);
    }
    @Override public ComicModel findComicForUpdate(Long comicId) {
        return copy(comicMapper.selectByIdForUpdate(comicId), ComicModel.class);
    }
    @Override public void updateComic(ComicModel model) {
        comicMapper.updateById(copy(model, Comic.class));
    }
    @Override public void updateImportTask(ImportTaskModel model) {
        importTaskMapper.updateById(copy(model, ImportTask.class));
    }
    @Override public long countChapters(Long comicId) { return chapterMapper.countByComicId(comicId); }
    @Override public List<Long> findTagIds(Long comicId) { return comicTagMapper.selectTagIdsByComicId(comicId); }
    @Override public TagModel findTag(String name, String type) {
        return copy(tagMapper.selectByNameAndType(name, type), TagModel.class);
    }
    @Override public void insertTag(TagModel model) { tagMapper.insert(copy(model, Tag.class)); }
    @Override public void insertComicTag(ComicTagModel model) {
        comicTagMapper.insert(copy(model, ComicTag.class));
    }
    @Override public List<CategorySnapshot> findCategories() {
        return categoryMapper.selectAllOrderedBySortOrder().stream()
                .map(category -> new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder()))
                .toList();
    }
    @Override public void insertCatalog(CatalogModel model) { catalogMapper.insert(copy(model, Catalog.class)); }
    @Override public void updateCatalog(CatalogModel model) { catalogMapper.updateById(copy(model, Catalog.class)); }
    @Override public void insertChapter(ChapterModel model) { chapterMapper.insert(copy(model, Chapter.class)); }
    @Override public List<ChapterModel> findChapters(Long comicId) {
        return chapterMapper.selectByComicId(comicId).stream()
                .map(entity -> copy(entity, ChapterModel.class)).toList();
    }
    @Override public ChapterModel findChapter(Long chapterId) {
        return copy(chapterMapper.selectById(chapterId), ChapterModel.class);
    }
    @Override public void updateChapter(ChapterModel model) { chapterMapper.updateById(copy(model, Chapter.class)); }
    @Override public int insertMediaBatch(List<MediaModel> mediaItems) {
        return mediaMapper.insertImportBatch(mediaItems.stream()
                .map(model -> copy(model, Media.class)).toList());
    }
    @Override public int markMediaFinalized(Long chapterId, String hqRelativePath) {
        return mediaMapper.markImportFinalizedByChapter(chapterId, hqRelativePath);
    }
    @Override public long countPendingMedia(List<Long> chapterIds, String hqStatus) {
        return mediaMapper.countByChapterIdsAndHqStatusNot(chapterIds, hqStatus);
    }
    @Override public List<MediaModel> findAllMedia(List<Long> chapterIds) {
        return mediaMapper.selectAllByChapterIds(chapterIds).stream()
                .map(entity -> copy(entity, MediaModel.class)).toList();
    }

    private static <Source, Target> Target copy(Source source, Class<Target> targetType) {
        if (source == null) {
            return null;
        }
        Target target = BeanUtils.instantiateClass(targetType);
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
