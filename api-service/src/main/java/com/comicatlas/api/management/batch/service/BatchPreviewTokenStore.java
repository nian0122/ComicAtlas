package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.management.batch.BatchReasonCode;
import com.comicatlas.api.management.batch.dto.BatchOperationRequest;
import com.comicatlas.api.management.batch.dto.BatchOperationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 危险操作 preview token 存储（进程内、带过期时间）。
 * <p>
 * token 绑定操作 + 目标指纹：指纹 = SHA-256(operation|sortedComicIds|payload)。
 * 创建任务时若条件变化（指纹不匹配）或过期 → 409。
 */
@Slf4j
@Component
public class BatchPreviewTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String fingerprint, long expiresAtEpochMillis) {
    }

    /**
     * 签发 token。
     *
     * @param request      批量请求（操作/负载）
     * @param sortedIds    已排序的目标漫画 id
     * @param ttlSeconds   有效期秒数
     * @return token 字符串；eligibleCount 为 0 时返回 null（无需确认）
     */
    public String issue(BatchOperationRequest request, List<Long> sortedIds, int ttlSeconds) {
        if (sortedIds.isEmpty()) {
            return null;
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        String fingerprint = fingerprint(request, sortedIds);
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        store.put(token, new Entry(fingerprint, expiresAt));
        return token;
    }

    /**
     * 校验 token：存在且未过期且指纹匹配。
     *
     * @return null 表示通过；否则返回 reasonCode（PREVIEW_TOKEN_EXPIRED / PREVIEW_CONDITION_CHANGED）
     */
    public String validate(BatchOperationRequest request, String token, List<Long> sortedIds) {
        Entry entry = store.get(token);
        if (entry == null) {
            return BatchReasonCode.PREVIEW_TOKEN_EXPIRED;
        }
        if (System.currentTimeMillis() > entry.expiresAtEpochMillis()) {
            store.remove(token);
            return BatchReasonCode.PREVIEW_TOKEN_EXPIRED;
        }
        String currentFingerprint = fingerprint(request, sortedIds);
        if (!entry.fingerprint().equals(currentFingerprint)) {
            return BatchReasonCode.PREVIEW_CONDITION_CHANGED;
        }
        store.remove(token);
        return null;
    }

    public String fingerprint(BatchOperationRequest request, List<Long> sortedIds) {
        StringBuilder sb = new StringBuilder(request.getOperation().name());
        for (Long id : sortedIds) {
            sb.append('|').append(id);
        }
        sb.append('|').append(canonicalPayload(request.getPayload()));
        return sha256(sb.toString());
    }

    private static String canonicalPayload(BatchOperationPayload payload) {
        if (payload == null) {
            return "";
        }
        return String.join("|",
                String.valueOf(payload.getCategoryId()),
                payload.getAddTagIds() == null ? "" : String.join(",", payload.getAddTagIds().stream()
                        .map(String::valueOf).sorted().toList()),
                nz(payload.getTitle()),
                nz(payload.getAuthor()),
                nz(payload.getDescription()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
