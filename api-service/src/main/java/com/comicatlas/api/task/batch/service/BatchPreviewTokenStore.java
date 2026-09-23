package com.comicatlas.api.task.batch.service;

import com.comicatlas.api.task.batch.enums.BatchReasonCode;
import com.comicatlas.api.task.batch.dto.BatchOperationRequest;
import com.comicatlas.api.task.batch.dto.BatchOperationPayloadDTO;
import com.comicatlas.api.shared.crypto.DigestService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
@lombok.Getter
public class BatchPreviewTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentMap<String, Entry> store = new ConcurrentHashMap<>();
    private final DigestService digestService;

    @lombok.Getter

    private static class Entry {
        private final String fingerprint;
        private final long expiresAtEpochMillis;
        public Entry(String fingerprint, long expiresAtEpochMillis) {
            this.fingerprint = fingerprint;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
        public String fingerprint() { return fingerprint; }
        public long expiresAtEpochMillis() { return expiresAtEpochMillis; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof Entry)) { return false; }
            Entry that = (Entry) other;
            return java.util.Objects.equals(fingerprint, that.fingerprint) && java.util.Objects.equals(expiresAtEpochMillis, that.expiresAtEpochMillis);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(fingerprint, expiresAtEpochMillis); }
        @Override
        public String toString() { return "Entry[" + "fingerprint=" + fingerprint + ", " + "expiresAtEpochMillis=" + expiresAtEpochMillis + "]"; }
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
        return digestService.sha256(sb.toString());
    }

    private static String canonicalPayload(BatchOperationPayloadDTO payload) {
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

}
