package com.comicatlas.api.common.storage;

import lombok.Data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * API 侧存储根 — 对应 application.yml 中 storage.roots 下的每个 key。
 * API 对 STAGING 可写，对其他根只读。
 *
 * <p>路径解析双层防线：第一道词法校验（normalize + startsWith，拦截 {@code ../} 穿越）；
 * 第二道真实路径 containment（toRealPath，拒绝经 symlink/junction/reparse point 逃出根）。
 */
@Data
public class ApiStorageRoot {
    private String type = "FILESYSTEM";
    private Path path;
    private boolean enabled = true;
    private boolean readOnly = true;

    /**
     * 安全解析相对路径，防御路径穿越攻击与 symlink/junction 越界。
     *
     * <p>调用方必须在本方法返回后立即执行 IO（delete/move/read 等），本方法每次调用都会
     * 重新执行真实路径 containment 校验，避免校验与 IO 之间链接被替换后越出根。
     *
     * @param relativePath 相对路径
     * @return 解析后的绝对路径
     * @throws PathTraversalException 路径穿越 root 边界，或经链接逃出根的真实边界
     */
    public Path resolve(String relativePath) {
        if (path == null) {
            throw new IllegalStateException("存储根路径未配置");
        }
        Path resolved = path.resolve(relativePath).normalize();
        // 第一道防线：词法校验（相对路径/绝对路径/../ 边界）
        if (!resolved.startsWith(path.normalize())) {
            throw new PathTraversalException("路径穿越拒绝: root=" + path + ", relative=" + relativePath);
        }
        // 第二道防线：真实路径 containment（根存在时；根不存在回退词法校验）
        assertRealPathContained(resolved, relativePath);
        return resolved;
    }

    /**
     * 第二道防线：真实路径 containment。
     * 根目录与目标均按真实路径（toRealPath）比较，拒绝经 symlink/junction/reparse point 逃出根的目标；
     * 目标不存在时按最近已存在父目录的真实路径校验（允许在根内安全创建尚不存在的目标）。
     * 根目录本身不存在时（初始化/配置缺失）回退词法校验，不阻断既有流程。
     */
    private void assertRealPathContained(Path resolved, String relativePath) {
        Path rootReal;
        try {
            rootReal = path.toRealPath();
        } catch (IOException e) {
            // 根目录尚不存在：无法建立真实边界，回退词法校验（第一道防线仍生效）
            return;
        }
        try {
            Path targetReal = realPathOf(resolved);
            if (!targetReal.startsWith(rootReal)) {
                throw new PathTraversalException(
                        "路径真实边界越界(可能经 symlink/junction/reparse point 逃出根): root=" + path
                                + ", realRoot=" + rootReal + ", target=" + resolved
                                + ", realTarget=" + targetReal + ", relative=" + relativePath);
            }
        } catch (NoSuchFileException e) {
            // 目标及其所有父级均不存在（极端场景）：词法校验已通过，按已存在祖先校验放行
        } catch (IOException e) {
            throw new PathTraversalException("存储根真实路径解析失败: root=" + path + ", target=" + resolved, e);
        }
    }

    /**
     * 计算目标的真实路径。目标存在时直接 toRealPath（追随 symlink/junction）；
     * 目标不存在时向上取最近已存在父目录的真实路径并拼回目标名，
     * 保证"根内尚不存在的目标"可安全创建，同时仍能拦截经根内 junction 指向根外父级的情况。
     */
    private static Path realPathOf(Path target) throws IOException {
        try {
            return target.toRealPath();
        } catch (NoSuchFileException e) {
            Path parent = target.getParent();
            if (parent == null) {
                throw e;
            }
            return realPathOf(parent).resolve(target.getFileName());
        }
    }

    public boolean exists() {
        return path != null && Files.exists(path);
    }

    /**
     * 判断指定路径与此存储根是否在同一文件系统卷上。
     */
    public boolean sameFileStore(Path other) {
        if (path == null || other == null) { return false; }
        try {
            return Files.getFileStore(path).equals(Files.getFileStore(other));
        } catch (Exception e) {
            return false;
        }
    }
}
