package com.comicatlas.persistence.reader.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReadingHistoryMapper extends BaseMapper<ReadingHistory> {

    /**
     * 按漫画唯一键原子写入最近阅读进度，避免并发请求在 select-then-insert 之间产生重复记录。
     *
     * @param history 阅读进度
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO reading_history (comic_id, chapter_id, page_number, created_at, updated_at)
        VALUES (#{comicId}, #{chapterId}, #{pageNumber}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE
            chapter_id = VALUES(chapter_id),
            page_number = VALUES(page_number),
            updated_at = CURRENT_TIMESTAMP
        """)
    int upsert(ReadingHistory history);
}
