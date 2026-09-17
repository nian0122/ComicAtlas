package com.comicatlas.api.task.application.port.in;

import com.comicatlas.api.task.domain.policy.AllowedOperations;

/** 媒体操作资格查询入站端口。 */
public interface MediaOperationEligibilityService {
    AllowedOperations forComic(Long comicId);
    AllowedOperations forChapter(Long chapterId);
    AllowedOperations forMedia(Long mediaId);
}
