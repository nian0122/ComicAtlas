package com.comicatlas.api.upload.infrastructure.adapter;

import com.comicatlas.api.upload.application.port.out.UploadStoragePort;
import com.comicatlas.api.upload.domain.RangeTracker;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.api.upload.application.support.DiskSpaceChecker;
import com.comicatlas.api.upload.infrastructure.config.UploadProperties;

// 条件更新由应用层维护会话状态机与并发边界，Mapper 仅执行参数化更新。
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.upload.infrastructure.persistence.mapper.UploadFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 分片上传存储服务 — 流式写入 STAGING/{sessionId}/{fileId}.part。
 * <p>
 * 不跟随客户端文件名拼路径（storageName 服务端生成）；乱序/重复分片通过
 * 区间合并处理；每个文件独立锁避免并发丢失区间更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadStorageAdapter implements UploadStoragePort {

    public static final String STAGING_KEY = "STAGING";
    public static final String PART_SUFFIX = ".part";

    private final ApiStorageProperties storageProperties;
    private final UploadFileMapper uploadFileMapper;
    private final UploadProperties uploadProperties;
    private final DiskSpaceChecker diskSpaceChecker;

    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    // ======================== 路径 ========================

    private ApiStorageRoot stagingRoot() {
        ApiStorageRoot root = storageProperties.getRoots().get(STAGING_KEY);
        if (root == null || !root.isEnabled()) {
            throw new BusinessException(503, "STAGING 存储根未配置");
        }
        return root;
    }

    public Path sessionDir(String sessionId) {
        return stagingRoot().resolve(sessionId);
    }

    public Path stagingPath(String sessionId, String storageName) {
        return stagingRoot().resolve(sessionId + "/" + storageName + PART_SUFFIX);
    }

    public void ensureStagingDir(String sessionId) {
        try {
            Files.createDirectories(sessionDir(sessionId));
        } catch (IOException e) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "创建 STAGING 目录失败: " + e.getMessage());
        }
    }

    // ======================== 磁盘空间 ========================

    /**
     * 校验空闲空间阈值：usable >= freeSpaceMinBytes 且 usable >= total * ratio。
     */
    public void ensureEnoughFreeSpace(long requiredBytes) {
        ApiStorageRoot root = stagingRoot();
        DiskSpaceChecker.SpaceInfo info = diskSpaceChecker.spaceInfo(root.getPath());
        long usable = info.usable();
        long total = info.total();
        long minBytes = uploadProperties.getFreeSpaceMinBytes();
        double ratio = uploadProperties.getFreeSpaceMinRatio();
        boolean ok = usable >= minBytes && usable >= (long) (total * ratio) && usable >= requiredBytes;
        if (!ok) {
            throw new BusinessException(507,
                    "磁盘空间不足: 可用 " + usable + " bytes, 需要 ≥ " + Math.max(minBytes, requiredBytes)
                            + " bytes 且 ≥ " + Math.round(ratio * 100) + "% 总容量");
        }
    }

    // ======================== 分片写入 ========================

    /**
     * 流式写入一个分片。返回合并后的已接收区间串。
     *
     * @param session     会话
     * @param file        目标文件（storageName 服务端生成）
     * @param start       分片起始偏移（含）
     * @param end         分片结束偏移（含）
     * @param total       文件声明总大小
     * @param chunkSha256 分片 SHA-256（hex，可空则跳过校验）
     * @param in          分片字节流
     */
    public WriteChunkResult writeChunk(String sessionId, Long fileId, String storageName, UploadSessionStatus sessionStatus,
                                       long fileSize, String receivedRanges, long start, long end, long total,
                                       String chunkSha256, InputStream in) {
        if (sessionStatus != UploadSessionStatus.ACTIVE) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话状态 " + sessionStatus + " 不允许上传分片");
        }
        if (start < 0 || end < start || end >= total) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range: bytes " + start + "-" + end + "/" + total);
        }
        long length = end - start + 1;
        if (length > uploadProperties.getChunkSize()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分片超出上限: " + length + " > " + uploadProperties.getChunkSize());
        }
        if (total != fileSize) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Content-Range 总大小与清单不符: " + total + " != " + fileSize);
        }

        ReentrantLock lock = fileLocks.computeIfAbsent(sessionId + ":" + fileId,
                k -> new ReentrantLock());
        lock.lock();
        try {
            ensureStagingDir(sessionId);
            Path path = stagingPath(sessionId, storageName);
            String actualHex = writePositional(path, start, in);
            if (chunkSha256 != null && !chunkSha256.isBlank()
                    && !chunkSha256.equalsIgnoreCase(actualHex)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "分片 SHA-256 校验失败: 声明=" + chunkSha256 + " 实际=" + actualHex);
            }
            String merged = RangeTracker.merge(receivedRanges, start, end);
            long received = maxEnd(merged) + 1;
            uploadFileMapper.updateReceivedRange(fileId, received, merged);
            return new WriteChunkResult(received, merged);
        } catch (IOException e) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "分片写入失败: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private String writePositional(Path path, long start, InputStream in) throws IOException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long offset = start;
                while ((n = in.read(buf)) > 0) {
                    messageDigest.update(buf, 0, n);
                    ByteBuffer wb = ByteBuffer.wrap(buf, 0, n);
                    while (wb.hasRemaining()) {
                        offset += channel.write(wb, offset);
                    }
                }
            }
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static long maxEnd(String ranges) {
        long max = -1;
        for (String part : ranges.split(";")) {
            int dash = part.indexOf('-');
            if (dash > 0) {
                try {
                    max = Math.max(max, Long.parseLong(part.substring(dash + 1)));
                } catch (NumberFormatException e) {
                    log.warn("解析 range 结束位置失败: {}", part, e);
                }
            }
        }
        return max;
    }

    // ======================== 清理 ========================

    public void deleteStagingDir(String sessionId) {
        Path dir = sessionDir(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("STAGING 清理失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("STAGING 目录清理失败: {}", dir, e);
        }
    }
}
