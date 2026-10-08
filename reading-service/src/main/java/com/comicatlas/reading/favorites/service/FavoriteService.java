package com.comicatlas.reading.favorites.service;

import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.reading.favorites.dto.FavoriteDTO;
import com.comicatlas.reading.favorites.persistence.FavoriteRow;
import com.comicatlas.reading.favorites.persistence.mapper.FavoriteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.ZoneOffset;
import java.util.List;

/** 喜欢列表查询与卡片装配，无跨层级的隐式标记。 */
@Service
@RequiredArgsConstructor
public class FavoriteService {
    private final FavoriteMapper favoriteMapper;
    private final FileUrlResolver fileUrlResolver;

    /** 有界分页，标记时间统一按 UTC 输出。 */
    public List<FavoriteDTO> list(String targetType, boolean oldest, int page, int size) {
        return favoriteMapper.selectFavorites(targetType, oldest, size + 1, (long) (page - 1) * size)
                .stream().map(row -> assemble(row, targetType)).toList();
    }

    private FavoriteDTO assemble(FavoriteRow row, String targetType) {
        String coverUrl = fileUrlResolver.resolveCover(row.getComicId());
        String previewUrl = "IMAGE".equals(row.getMediaType())
                ? fileUrlResolver.resolve(row.getPreviewRoot(), row.getPreviewPath()) : coverUrl;
        return new FavoriteDTO(row.getId(), targetType, row.getComicId(), row.getComicTitle(),
                row.getChapterId(), row.getTitle(), row.getPageNumber(), row.getMediaType(), row.getDuration(),
                coverUrl, previewUrl, row.getReactionAt() == null ? null
                : row.getReactionAt().toInstant(ZoneOffset.UTC), row.getLastReadChapterId(),
                row.getLastReadPageNumber());
    }
}
