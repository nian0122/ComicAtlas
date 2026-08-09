package com.comicatlas.api.importer.exception;

/**
 * 导入 metadata 校验失败（typed-fail）。
 * <p>
 * metadata 结构不合法、catalogIndex/parentIndex 越界等场景抛出本异常，
 * 触发事务回滚并进入 MQ DLQ，杜绝静默挂根/丢数据。
 */
public class ImportMetadataException extends RuntimeException {

    public ImportMetadataException(String message) {
        super(message);
    }
}
