package com.comicatlas.reading.reader.assembler;

import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.reading.reader.dto.ReaderDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 阅读器响应装配器，集中处理媒体 URL、状态和文件名回退规则。 */
@Component
@RequiredArgsConstructor
public class ReaderAssembler {
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    private static final String LQ_STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    private final FileUrlResolver fileUrlResolver;

    public ReaderDTO assemble(Chapter chapter, List<Media> mediaItems, Long previousChapterId,
                              Long nextChapterId) {
        ReaderDTO response = new ReaderDTO();
        response.setChapterId(chapter.getId());
        response.setComicId(chapter.getComicId());
        response.setChapterTitle(chapter.getTitle());
        response.setPages(mediaItems.stream().map(this::assembleMedia).toList());
        response.setTotal(mediaItems.size());
        response.setPrevChapterId(previousChapterId);
        response.setNextChapterId(nextChapterId);
        return response;
    }

    private ReaderDTO.MediaItemDTO assembleMedia(Media media) {
        ReaderDTO.MediaItemDTO item = new ReaderDTO.MediaItemDTO();
        item.setId(media.getId());
        item.setPageNumber(media.getPageNumber());
        item.setFileName(extractFileName(media.getHqPath() != null ? media.getHqPath() : media.getLqPath()));
        item.setHqUrl(fileUrlResolver.resolve(media));
        item.setHqStatus(media.getHqStatus() == null ? null : media.getHqStatus().name());
        item.setMediaType(media.getMediaType());
        item.setDuration(media.getDuration());
        item.setContainer(media.getContainer());
        item.setVideoCodec(media.getVideoCodec());
        item.setAudioCodec(media.getAudioCodec());
        if (MEDIA_TYPE_VIDEO.equals(media.getMediaType())) {
            item.setLqUrl(null);
            item.setLqStatus(LQ_STATUS_NOT_APPLICABLE);
        } else {
            item.setLqUrl(fileUrlResolver.resolveLq(media));
            item.setLqStatus(media.getLqStatus() == null ? null : media.getLqStatus().name());
        }
        item.setWidth(media.getWidth());
        item.setHeight(media.getHeight());
        item.setHqSize(media.getHqSize());
        item.setLqSize(media.getLqSize());
        item.setTranscodeStatus(media.getTranscodeStatus() == null ? null : media.getTranscodeStatus().name());
        return item;
    }

    private String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}
