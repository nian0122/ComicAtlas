package com.comicatlas.api.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 已接收区间跟踪 — 以 "0-65535;131072-196607" 形式表示已接收字节区间。
 * 支持乱序、重复分片（重叠合并）与缺口检测。
 */
public final class RangeTracker {

    private static final Logger log = LoggerFactory.getLogger(RangeTracker.class);

    private RangeTracker() {}

    /**
     * 合并新区间 [start, end] 到既有区间串，返回归一化后的区间串。
     */
    public static String merge(String existing, long start, long end) {
        List<long[]> ranges = new ArrayList<>(parse(existing));
        ranges.add(new long[]{start, end});
        return serialize(compact(ranges));
    }

    /**
     * 判断区间串是否完整覆盖 [0, size-1]。
     */
    public static boolean isFullyReceived(String ranges, long size) {
        if (size <= 0) return false;
        List<long[]> list = parse(ranges);
        if (list.isEmpty()) return false;
        if (list.get(0)[0] != 0) return false;
        long expected = 0;
        for (long[] r : list) {
            if (r[0] > expected) return false;
            expected = Math.max(expected, r[1] + 1);
            if (expected >= size) return true;
        }
        return expected >= size;
    }

    /**
     * 返回缺失区间列表（覆盖 [0, size-1] 之外的缺口）。
     */
    public static List<long[]> missingRanges(String ranges, long size) {
        List<long[]> missing = new ArrayList<>();
        long expected = 0;
        for (long[] r : parse(ranges)) {
            if (r[0] > expected) {
                missing.add(new long[]{expected, r[0] - 1});
            }
            expected = Math.max(expected, r[1] + 1);
        }
        if (expected < size) {
            missing.add(new long[]{expected, size - 1});
        }
        return missing;
    }

    private static List<long[]> compact(List<long[]> input) {
        List<long[]> sorted = new ArrayList<>(input);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] r : sorted) {
            if (out.isEmpty()) {
                out.add(new long[]{r[0], r[1]});
                continue;
            }
            long[] last = out.get(out.size() - 1);
            if (r[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                out.add(new long[]{r[0], r[1]});
            }
        }
        return out;
    }

    private static String serialize(List<long[]> ranges) {
        StringBuilder sb = new StringBuilder();
        for (long[] r : ranges) {
            if (sb.length() > 0) sb.append(';');
            sb.append(r[0]).append('-').append(r[1]);
        }
        return sb.toString();
    }

    private static List<long[]> parse(String ranges) {
        List<long[]> out = new ArrayList<>();
        if (ranges == null || ranges.isBlank()) {
            return out;
        }
        for (String part : ranges.split(";")) {
            int dash = part.indexOf('-');
            if (dash <= 0) continue;
            try {
                long s = Long.parseLong(part.substring(0, dash));
                long e = Long.parseLong(part.substring(dash + 1));
                if (s <= e) out.add(new long[]{s, e});
            } catch (NumberFormatException e) { log.warn("解析 range 段失败: {}", part, e); }
        }
        return out;
    }
}
