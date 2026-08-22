package com.comicatlas.api.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.outbox.entity.InboxReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * Inbox 收据 Mapper。
 */
@Mapper
public interface InboxReceiptMapper extends BaseMapper<InboxReceipt> {

    /**
     * 删除 processed_at 超过指定天数的记录。
     */
    @Delete("DELETE FROM inbox_receipt WHERE processed_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteProcessedOlderThan(@Param("days") int days);
}
