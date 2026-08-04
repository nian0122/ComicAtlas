package com.comicatlas.api.upload;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.common.storage.PathTraversalException;
import com.comicatlas.api.upload.entity.UploadFile;
import com.comicatlas.api.upload.entity.UploadSession;
import com.comicatlas.api.upload.mapper.UploadFileMapper;
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
public class UploadStorageService {

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

    public Path sessionDir(UploadSession session) {
        return stagingRoot().resolve(session.getSessionId());
    }

    public Path stagingPath(UploadSession session, UploadFile file) {
        return stagingRoot().resolve(session.getSessionId() + "/" + file.getStorageName() + PART_SUFFIX);
    }

    public void ensureStagingDir(UploadSession session) {
        try {
            Files.createDirectories(sessionDir(session));
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
    public String writeChunk(UploadSession session, UploadFile file,
                             long start, long end, long total,
                             String chunkSha256, InputStream in) {
        if (!UploadSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话状态 " + session.getStatus() + " 不允许上传分片");
        }
        if (start < 0 || end < start || end >= total) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range: bytes " + start + "-" + end + "/" + total);
        }
        long length = end - start + 1;
        if (length > uploadProperties.getChunkSize()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分片超出上限: " + length + " > " + uploadProperties.getChunkSize());
        }
        if (total != file.getSizeBytes()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Content-Range 总大小与清单不符: " + total + " != " + file.getSizeBytes());
        }

        ReentrantLock lock = fileLocks.computeIfAbsent(session.getSessionId() + ":" + file.getFileId(),
                k -> new ReentrantLock());
        lock.lock();
        try {
            ensureStagingDir(session);
            Path path = stagingPath(session, file);
            String actualHex = writePositional(path, start, in);
            if (chunkSha256 != null && !chunkSha256.isBlank()
                    && !chunkSha256.equalsIgnoreCase(actualHex)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "分片 SHA-256 校验失败: 声明=" + chunkSha256 + " 实际=" + actualHex);
            }
            String merged = RangeTracker.merge(file.getReceivedRanges(), start, end);
            long received = maxEnd(merged) + 1;
            uploadFileMapper.update(null, new LambdaUpdateWrapper<UploadFile>()
                    .eq(UploadFile::getId, file.getId())
                    .set(UploadFile::getReceivedBytes, received)
                    .set(UploadFile::getReceivedRanges, merged));
            file.setReceivedBytes(received);
            file.setReceivedRanges(merged);
            return merged;
        } catch (IOException e) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "分片写入失败: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private String writePositional(Path path, long start, InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long offset = start;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    ByteBuffer wb = ByteBuffer.wrap(buf, 0, n);
                    while (wb.hasRemaining()) {
                        offset += channel.write(wb, offset);
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
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
                } catch (NumberFormatException e) { log.warn("解析 range 结束位置失败: {}", part, e); }
            }
        }
        return max;
    }

    // ======================== 清理 ========================

    public void deleteStagingDir(UploadSession session) {
        Path dir = sessionDir(session);
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
