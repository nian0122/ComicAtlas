package com.comicatlas.worker.importer;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 自然路径比较器：把文件名拆成数字段与非数字段，逐段比较。
 * <p>
 * 排序向量：第1话 &lt; 第2话 &lt; 第10话、1 &lt; 01 &lt; 001、1-2 &lt; 1-10 &lt; 2-1。
 * 规则：
 * <ol>
 *   <li>数字段按数值比较，数值相等时按数字串长度（短串优先，实现 1 &lt; 01 &lt; 001）；</li>
 *   <li>数字段优先于非数字段；</li>
 *   <li>非数字段按大小写敏感原名比较；</li>
 *   <li>全部段相等时按规范化相对路径兜底。</li>
 * </ol>
 */
public final class NaturalPathComparator implements Comparator<Path> {

    public static final NaturalPathComparator INSTANCE = new NaturalPathComparator();

    private NaturalPathComparator() {
    }

    @Override
    public int compare(Path a, Path b) {
        return compareNames(a.getFileName() == null ? "" : a.getFileName().toString(),
                b.getFileName() == null ? "" : b.getFileName().toString());
    }

    /** 按文件名自然排序的比较器（用于 DirectoryTree 等按 name 排序）。 */
    public static Comparator<String> nameComparator() {
        return NaturalPathComparator::compareNames;
    }

    public static int compareNames(String a, String b) {
        List<Segment> ta = tokenize(a);
        List<Segment> tb = tokenize(b);
        int limit = Math.min(ta.size(), tb.size());
        for (int i = 0; i < limit; i++) {
            int c = ta.get(i).compareTo(tb.get(i));
            if (c != 0) {
                return c;
            }
        }
        if (ta.size() != tb.size()) {
            return Integer.compare(ta.size(), tb.size());
        }
        // 全段相等：大小写敏感原名兜底
        int c = a.compareTo(b);
        if (c != 0) {
            return c;
        }
        // 规范化相对路径兜底（同名不同目录场景）
        return Path.of(a).normalize().compareTo(Path.of(b).normalize());
    }

    /** 把字符串切分为数字段与非数字段的交替序列。 */
    private static List<Segment> tokenize(String s) {
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int start = i;
            boolean digit = Character.isDigit(s.charAt(i));
            while (i < s.length() && Character.isDigit(s.charAt(i)) == digit) {
                i++;
            }
            segments.add(new Segment(digit, s.substring(start, i)));
        }
        return segments;
    }

    /** 一段数字或一段非数字原文。 */
    private record Segment(boolean numeric, String raw) implements Comparable<Segment> {

        @Override
        public int compareTo(Segment other) {
            if (numeric && other.numeric) {
                // 数值比较（去掉前导零后仍可比较）
                int c = new BigInteger(raw).compareTo(new BigInteger(other.raw));
                if (c != 0) {
                    return c;
                }
                // 数值相等：数字串长度短串优先（1 < 01 < 001）
                c = Integer.compare(raw.length(), other.raw.length());
                if (c != 0) {
                    return c;
                }
                // 长度相同：原文兜底（如 "01" vs "01"）
                return raw.compareTo(other.raw);
            }
            if (numeric) {
                return -1; // 数字段优先
            }
            if (other.numeric) {
                return 1;
            }
            // 非数字段：大小写敏感原名比较
            return raw.compareTo(other.raw);
        }
    }
}
