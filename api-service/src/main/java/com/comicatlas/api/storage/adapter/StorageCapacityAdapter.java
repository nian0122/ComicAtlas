package com.comicatlas.api.storage.adapter;

import com.comicatlas.contract.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 封装受管存储目录的容量读取，避免查询服务承担磁盘遍历细节。 */
@Component
public class StorageCapacityAdapter {

    public long directorySize(Path directory) {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            long totalBytes = 0L;
            var pathIterator = paths.filter(Files::isRegularFile).iterator();
            while (pathIterator.hasNext()) {
                try {
                    totalBytes += Files.size(pathIterator.next());
                } catch (IOException exception) {
                    throw new BusinessException("读取存储容量失败", exception);
                }
            }
            return totalBytes;
        } catch (IOException exception) {
            throw new BusinessException("扫描存储容量失败", exception);
        }
    }
}
