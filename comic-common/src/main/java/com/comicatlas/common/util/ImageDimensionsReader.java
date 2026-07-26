package com.comicatlas.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 零依赖图片尺寸读取器 — 通过解析文件头直接获取 JPEG/PNG/GIF/WebP/BMP 尺寸。
 * 作为 javax.imageio.ImageIO 无法识别格式时的可靠回退。
 */
public final class ImageDimensionsReader {

    private ImageDimensionsReader() {}

    /**
     * @return [width, height]，任一为 0 表示读取失败
     */
    public static int[] read(Path file) {
        try {
            byte[] buf = Files.readAllBytes(file);
            if (buf.length < 24) return EMPTY;
            return parse(buf);
        } catch (IOException e) {
            return EMPTY;
        }
    }

    private static final int[] EMPTY = new int[]{0, 0};

    static int[] parse(byte[] buf) {
        if (buf.length < 12) return EMPTY;

        // JPEG: FF D8 FF
        if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8) {
            return parseJpeg(buf);
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (buf[0] == (byte) 0x89 && buf[1] == 'P' && buf[2] == 'N' && buf[3] == 'G') {
            int w = readIntBE(buf, 16);
            int h = readIntBE(buf, 20);
            return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
        }
        // GIF: 47 49 46 38 (GIF8)
        if (buf[0] == 'G' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == '8') {
            int w = readShortLE(buf, 6);
            int h = readShortLE(buf, 8);
            return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
        }
        // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF .... WEBP)
        if (buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F'
                && buf[8] == 'W' && buf[9] == 'E' && buf[10] == 'B' && buf[11] == 'P') {
            return parseWebp(buf);
        }
        // BMP: 42 4D
        if (buf[0] == 'B' && buf[1] == 'M') {
            int w = readIntLE(buf, 18);
            int h = readIntLE(buf, 22);
            return w > 0 ? new int[]{w, Math.abs(h)} : EMPTY;
        }
        return EMPTY;
    }

    private static int[] parseJpeg(byte[] buf) {
        int i = 2;
        int len = buf.length;
        while (i < len - 9) {
            int b = buf[i] & 0xFF;
            if (b != 0xFF) break;
            int marker = buf[i + 1] & 0xFF;
            // SOF0, SOF1, SOF2 (baseline, extended, progressive)
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                int h = readShortBE(buf, i + 5);
                int w = readShortBE(buf, i + 7);
                return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
            }
            // SOS marker (start of scan) — entropy-coded data follows, stop
            if (marker == 0xDA) break;
            // Skip marker segment
            int segLen = readShortBE(buf, i + 2);
            i += 2 + segLen;
        }
        return EMPTY;
    }

    private static int[] parseWebp(byte[] buf) {
        if (buf.length < 30) return EMPTY;
        // VP8 (lossy): "VP8 " at offset 12
        if (buf[12] == 'V' && buf[13] == 'P' && buf[14] == '8' && buf[15] == ' ') {
            int w = readShortLE(buf, 26) & 0x3FFF;
            int h = readShortLE(buf, 28) & 0x3FFF;
            return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
        }
        // VP8L (lossless): "VP8L" at offset 12
        if (buf[12] == 'V' && buf[13] == 'P' && buf[14] == '8' && buf[15] == 'L') {
            int b0 = buf[21] & 0xFF, b1 = buf[22] & 0xFF,
                b2 = buf[23] & 0xFF, b3 = buf[24] & 0xFF;
            int w = 1 + (((b1 & 0x3F) << 8) | b0);
            int h = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
            return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
        }
        // VP8X (extended): "VP8X" at offset 12
        if (buf[12] == 'V' && buf[13] == 'P' && buf[14] == '8' && buf[15] == 'X') {
            int w = 1 + ((buf[24] & 0xFF) | ((buf[25] & 0xFF) << 8) | ((buf[26] & 0xFF) << 16));
            int h = 1 + ((buf[27] & 0xFF) | ((buf[28] & 0xFF) << 8) | ((buf[29] & 0xFF) << 16));
            return w > 0 && h > 0 ? new int[]{w, h} : EMPTY;
        }
        return EMPTY;
    }

    // --- big-endian helpers ---
    private static int readShortBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    private static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
             | ((buf[offset + 1] & 0xFF) << 16)
             | ((buf[offset + 2] & 0xFF) << 8)
             | (buf[offset + 3] & 0xFF);
    }

    // --- little-endian helpers ---
    private static int readShortLE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }

    private static int readIntLE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
             | ((buf[offset + 1] & 0xFF) << 8)
             | ((buf[offset + 2] & 0xFF) << 16)
             | ((buf[offset + 3] & 0xFF) << 24);
    }
}
