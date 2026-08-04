package com.comicatlas.api.upload;

import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 媒体类型探测 — 扩展名/MIME 白名单 + 魔数（magic bytes）双重校验。
 * <p>
 * 不接受客户端路径：仅从文件名提取扩展名，文件内容以魔数为准。
 */
@Component
public class MediaTypeDetector {

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mkv", "mov", "avi");

    /** 检测结果 */
    public record Detection(String mediaType, String ext, String container) {}

    /**
     * 校验客户端文件名并返回规范化扩展名（不含点，小写）。
     * 拒绝路径穿越/控制字符/未知扩展。
     */
    public String validateAndExtractExtension(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "文件名不能为空");
        }
        String base = name.replace('\\', '/');
        if (base.contains("/") || base.contains("..") || base.indexOf('\0') >= 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法文件名: " + name);
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "文件缺少扩展名: " + name);
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXT.contains(ext) && !VIDEO_EXT.contains(ext)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的扩展名: ." + ext);
        }
        return ext;
    }

    /**
     * 校验客户端声明的 Content-Type 是否在白名单内。
     */
    public void validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Content-Type 不能为空");
        }
        String ct = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        boolean ok = ct.startsWith("image/") || ct.startsWith("video/");
        if (!ok) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的 Content-Type: " + contentType);
        }
    }

    /**
     * 探测文件魔数，返回媒体类型与容器。与声明扩展名不一致时抛出业务异常。
     *
     * @param file  已上传完整的 STAGING .part 文件
     * @param ext   声明扩展名（小写，不含点）
     * @return Detection(IMAGE/VIDEO, ext, container)
     */
    public Detection detect(Path file, String ext) {
        byte[] head = readHead(file);
        String magicMediaType = classifyMagic(head);
        if (magicMediaType == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "无法识别文件内容（魔数校验失败）: " + file.getFileName());
        }
        boolean extImage = IMAGE_EXT.contains(ext);
        boolean extVideo = VIDEO_EXT.contains(ext);
        if (("IMAGE".equals(magicMediaType) && !extImage) || ("VIDEO".equals(magicMediaType) && !extVideo)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "文件扩展名与内容类型不一致: ." + ext + " vs " + magicMediaType.toLowerCase(Locale.ROOT));
        }
        String container = "VIDEO".equals(magicMediaType) ? ext : null;
        return new Detection(magicMediaType, ext, container);
    }

    /** 分类魔数，返回 IMAGE / VIDEO / null(未知) */
    private String classifyMagic(byte[] b) {
        if (b == null) {
            return null;
        }
        if (matches(b, new int[]{0xFF, 0xD8, 0xFF})) return "IMAGE";                       // JPEG
        if (matches(b, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) return "IMAGE"; // PNG
        if (startsWithAscii(b, 0, "GIF8")) return "IMAGE";                                   // GIF
        if (startsWithAscii(b, 0, "RIFF") && startsWithAscii(b, 8, "WEBP")) return "IMAGE";  // WebP
        if (b[0] == 0x42 && b[1] == 0x4D) return "IMAGE";                                    // BMP
        if (startsWithAscii(b, 4, "ftyp")
                && (startsWithAscii(b, 8, "avif") || startsWithAscii(b, 8, "avis"))) return "IMAGE"; // AVIF
        if (startsWithAscii(b, 4, "ftyp")) return "VIDEO";                                   // MP4/MOV
        if (startsWithAscii(b, 0, "RIFF") && startsWithAscii(b, 8, "AVI ")) return "VIDEO";  // AVI
        if (matches(b, new int[]{0x1A, 0x45, 0xDF, 0xA3})) return "VIDEO";                   // WebM/MKV (EBML)
        return null;
    }

    private byte[] readHead(Path file) {
        if (!Files.exists(file)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "暂存文件不存在: " + file.getFileName());
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[16];
            int n = in.readNBytes(buf, 0, buf.length);
            if (n < 4) {
                return null;
            }
            byte[] head = new byte[n];
            System.arraycopy(buf, 0, head, 0, n);
            return head;
        } catch (IOException e) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "读取暂存文件失败: " + file.getFileName());
        }
    }

    private static boolean matches(byte[] b, int[] magic) {
        if (b.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] b, int offset, String s) {
        if (b.length < offset + s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((char) b[offset + i] != s.charAt(i)) return false;
        }
        return true;
    }
}
