package com.comicatlas.worker.file.archive;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分卷 ZIP/CBZ 卷解析器 — 以最终 {@code .zip/.cbz} 文件为唯一入口，枚举同目录同 basename 的
 * {@code .z01..zNN} 连续卷，返回「数字序列 + 最后卷」的有序路径列表。
 *
 * <p>校验规则：只接受最终 {@code .zip} 作为入口；拒绝缺号（如存在 .z01/.z03 缺 .z02）、
 * 重复卷（同名序号出现两次）、超过 {@code .z99} 上限的卷；每个候选卷（含主 .zip）都必须是
 * 非符号链接的普通文件。单个 {@code .zip} 无 {@code .zNN} 兄弟时视为单卷，仅返回主文件。
 */
public final class ZipVolumeResolver {

    /** 分卷最大序号（.z01..z99，含主 .zip 共最多 100 卷）。 */
    private static final int MAX_SEGMENT_NUMBER = 99;
    /** 标准两位分卷后缀：{@code .z} + 两位数字。 */
    private static final Pattern TWO_DIGIT_VOLUME = Pattern.compile("\\.z(\\d{2})$");
    /** 任意位数分卷后缀（用于识别并拒绝 .z1/.z100 等非法命名）。 */
    private static final Pattern ANY_DIGIT_VOLUME = Pattern.compile("\\.z\\d+$");

    private ZipVolumeResolver() {
    }

    /**
     * 解析主 ZIP 的有序分卷列表。
     *
     * @param mainZip 最终 {@code .zip} 文件路径（唯一合法入口）
     * @return 单卷 {@code [mainZip]}；分卷 {@code [.z01, .z02, ..., .zip]}（数字序列 + 最后卷）
     * @throws IllegalArgumentException 入口不是 .zip、遇到符号链接/非普通文件、卷命名非法、
     *                                  序号重复、序号不连续（缺号）或超过 .z99 上限
     * @throws IOException              目录枚举或文件属性读取失败
     */
    public static List<Path> resolve(Path mainZip) throws IOException {
        Path normalized = mainZip.toAbsolutePath().normalize();
        String fileName = normalized.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String extension = archiveExtension(lowerName);
        if (extension == null) {
            throw new IllegalArgumentException("只接受最终 .zip/.cbz 分卷作为入口: " + normalized);
        }
        requireRegularFile(normalized, "主 " + extension);

        String baseName = fileName.substring(0, fileName.length() - extension.length());
        Path dir = normalized.getParent();

        Map<Integer, Path> numberedSegments = new TreeMap<>();
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(dir)) {
            for (Path sibling : siblings) {
                String name = sibling.getFileName().toString();
                if (!name.regionMatches(true, 0, baseName, 0, baseName.length())) {
                    continue;
                }
                String suffix = name.substring(baseName.length()).toLowerCase(Locale.ROOT);
                Matcher twoDigits = TWO_DIGIT_VOLUME.matcher(suffix);
                if (!twoDigits.find()) {
                    if (ANY_DIGIT_VOLUME.matcher(suffix).find()) {
                        throw new IllegalArgumentException("分卷命名非法（须 .z01..z99 两位编号）: " + sibling);
                    }
                    continue;
                }
                int number = Integer.parseInt(twoDigits.group(1));
                if (numberedSegments.putIfAbsent(number, sibling) != null) {
                    throw new IllegalArgumentException("重复分卷序号 .z" + pad(number) + ": " + sibling);
                }
            }
        }

        if (numberedSegments.isEmpty()) {
            return List.of(normalized);
        }

        List<Path> ordered = new ArrayList<>(numberedSegments.size() + 1);
        int expected = 1;
        for (Map.Entry<Integer, Path> entry : numberedSegments.entrySet()) {
            int number = entry.getKey();
            Path segment = entry.getValue();
            if (number > MAX_SEGMENT_NUMBER) {
                throw new IllegalArgumentException(
                        "分卷序号超过 .z" + MAX_SEGMENT_NUMBER + " 上限: " + segment);
            }
            if (number != expected) {
                throw new IllegalArgumentException(
                        "分卷序号不连续，缺少 .z" + pad(expected) + ": " + normalized);
            }
            requireRegularFile(segment, "分卷 .z" + pad(number));
            ordered.add(segment);
            expected++;
        }
        ordered.add(normalized);
        return List.copyOf(ordered);
    }

    private static String archiveExtension(String lowerName) {
        if (lowerName.endsWith(".zip")) {
            return ".zip";
        }
        if (lowerName.endsWith(".cbz")) {
            return ".cbz";
        }
        return null;
    }

    private static void requireRegularFile(Path path, String role) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(role + "是符号链接，拒绝: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(role + "不是普通文件: " + path);
        }
    }

    private static String pad(int number) {
        return number < 10 ? "0" + number : Integer.toString(number);
    }
}
