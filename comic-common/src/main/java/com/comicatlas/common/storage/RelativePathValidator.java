package com.comicatlas.common.storage;

/**
 * 存储相对路径校验：只允许正斜杠分隔的相对路径。
 * 拒绝绝对路径（/ 开头或盘符如 C:）、反斜杠路径与目录穿越（..）。
 * 供 metadata.hqPath 与目录预览 relativePath 在构建/解析边界共用。
 */
public final class RelativePathValidator {

    /** 盘符前缀正则（如 C:、D:），匹配即视为绝对路径。 */
    private static final String DRIVE_PREFIX_REGEX = "^[a-zA-Z]:";

    private RelativePathValidator() {
    }

    /**
     * 校验并原样返回相对路径；{@code null} 表示字段缺省（旧 JSON 无该字段），允许通过。
     *
     * @param path 待校验的相对路径，可为 null
     * @return 原样返回校验通过的相对路径；null 入参返回 null
     * @throws InvalidRelativePathException 路径为空、绝对、含反斜杠或含 .. 穿越时抛出
     */
    public static String requireRelativeForwardSlash(String path) {
        if (path == null) {
            return null;
        }
        if (path.isBlank()) {
            throw new InvalidRelativePathException("相对路径不能为空");
        }
        if (path.indexOf('\\') >= 0) {
            throw new InvalidRelativePathException("仅允许正斜杠相对路径，禁止反斜杠: " + path);
        }
        if (path.startsWith("/") || path.matches(DRIVE_PREFIX_REGEX + ".*")) {
            throw new InvalidRelativePathException("禁止绝对路径: " + path);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new InvalidRelativePathException("禁止目录穿越（..）: " + path);
            }
        }
        return path;
    }
}
