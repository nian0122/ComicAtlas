package com.comicatlas.api.exporter.service;

import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.exporter.entity.ExportTask;
import com.comicatlas.api.exporter.mapper.ExportTaskMapper;
import com.comicatlas.api.storage.dto.ExportArtifactVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出分卷清单服务测试 — 卷发现、安全校验（路径穿越/符号链接/缺卷/大小漂移）与状态码映射。
 */
class ExportArtifactServiceTest {

    @TempDir
    Path tempDir;

    private final ExportTaskMapper taskMapper = mock(ExportTaskMapper.class);

    private ExportArtifactService service() {
        return new ExportArtifactService(taskMapper, storageProperties(), new ExportZipVolumeResolver());
    }

    private ExportArtifactService service(ExportZipVolumeResolver resolver) {
        return new ExportArtifactService(taskMapper, storageProperties(), resolver);
    }

    private ApiStorageProperties storageProperties() {
        ApiStorageRoot exportRoot = new ApiStorageRoot();
        exportRoot.setPath(tempDir);
        ApiStorageProperties props = new ApiStorageProperties();
        props.setRoots(Map.of("EXPORT", exportRoot));
        return props;
    }

    private ExportTask task(Long id, String status, String outputPath, Long outputSize) {
        ExportTask task = new ExportTask();
        task.setId(id);
        task.setStatus(com.comicatlas.api.common.enums.ExportTaskStatus.valueOf(status));
        task.setOutputRoot("EXPORT");
        task.setOutputPath(outputPath);
        task.setOutputSize(outputSize);
        return task;
    }

    private Path writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    void listArtifacts_按z01到zip顺序返回且大小总和等于outputSize() throws Exception {
        writeFile("7/base.z01", "aaa");
        writeFile("7/base.z02", "bbbbb");
        writeFile("7/base.zip", "cc");
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/base.zip", 10L));

        List<ExportArtifactVO> artifacts = service().listArtifacts(7L);

        assertThat(artifacts).hasSize(3);
        assertThat(artifacts.get(0).getIndex()).isEqualTo(1);
        assertThat(artifacts.get(0).getFileName()).isEqualTo("base.z01");
        assertThat(artifacts.get(0).getSize()).isEqualTo(3L);
        assertThat(artifacts.get(0).getLastSegment()).isFalse();
        assertThat(artifacts.get(1).getIndex()).isEqualTo(2);
        assertThat(artifacts.get(1).getFileName()).isEqualTo("base.z02");
        assertThat(artifacts.get(1).getSize()).isEqualTo(5L);
        assertThat(artifacts.get(1).getLastSegment()).isFalse();
        assertThat(artifacts.get(2).getIndex()).isEqualTo(3);
        assertThat(artifacts.get(2).getFileName()).isEqualTo("base.zip");
        assertThat(artifacts.get(2).getSize()).isEqualTo(2L);
        assertThat(artifacts.get(2).getLastSegment()).isTrue();

        long sum = artifacts.stream().mapToLong(ExportArtifactVO::getSize).sum();
        assertThat(sum).isEqualTo(10L);
        // physicalPath 为 EXPORT 根下解析出的真实绝对路径，不包含逻辑根名与 ../
        assertThat(Path.of(artifacts.get(2).getPhysicalPath())).isEqualTo(tempDir.resolve("7/base.zip"));
        assertThat(artifacts.get(2).getPhysicalPath()).doesNotStartWith("EXPORT");
        assertThat(artifacts.get(2).getPhysicalPath()).doesNotContain("..");
    }

    @Test
    void listArtifacts_单个zip无分卷兄弟时仅返回一项() throws Exception {
        writeFile("8/base.zip", "hello");
        when(taskMapper.selectById(8L)).thenReturn(task(8L, "SUCCESS", "8/base.zip", 5L));

        List<ExportArtifactVO> artifacts = service().listArtifacts(8L);

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).getIndex()).isEqualTo(1);
        assertThat(artifacts.get(0).getFileName()).isEqualTo("base.zip");
        assertThat(artifacts.get(0).getLastSegment()).isTrue();
    }

    @Test
    void listArtifacts_任务不存在返回404() {
        when(taskMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(99L));

        assertThat(ex.getCode()).isEqualTo(404);
    }

    @Test
    void listArtifacts_任务未完成返回409() {
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "RUNNING", "7/base.zip", 10L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
    }

    @Test
    void listArtifacts_路径穿越返回409且不泄露根路径() {
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "../evil.zip", 10L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
        assertThat(ex.getMessage()).doesNotContain("evil.zip");
    }

    @Test
    void listArtifacts_主zip缺失返回404() {
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/missing.zip", 10L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(404);
    }

    @Test
    void listArtifacts_分卷序号缺号返回409() throws Exception {
        writeFile("7/base.z01", "aaa");
        writeFile("7/base.z03", "ccc");
        writeFile("7/base.zip", "zip");
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/base.zip", 100L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
    }

    @Test
    void listArtifacts_删除中间卷导致大小漂移返回409() throws Exception {
        writeFile("7/base.z01", "aaa");
        // 删除 base.z02
        writeFile("7/base.zip", "cc");
        // outputSize 仍为三卷总和
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/base.zip", 10L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
    }

    @Test
    void listArtifacts_符号链接分卷被拒绝返回409() throws Exception {
        writeFile("7/base.zip", "zip");
        writeFile("7/base.z01", "aaa");
        ExportZipVolumeResolver resolver = mock(ExportZipVolumeResolver.class);
        when(resolver.resolve(any(Path.class)))
                .thenThrow(new IllegalArgumentException("分卷 .z02 是符号链接，拒绝: base.z02"));
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/base.zip", 100L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service(resolver).listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
    }

    @Test
    void listArtifacts_真实符号链接分卷被拒绝返回409() throws Exception {
        Assumptions.assumeTrue(symlinkSupported(),
                "当前环境无法创建符号链接（需管理员或开发者模式），跳过真实符号链接用例");
        writeFile("7/base.zip", "zip");
        Path target = writeFile("7/base.z01", "aaa");
        Files.createSymbolicLink(tempDir.resolve("7").resolve("base.z02"), target);
        when(taskMapper.selectById(7L)).thenReturn(task(7L, "SUCCESS", "7/base.zip", 100L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service().listArtifacts(7L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).doesNotContain(tempDir.toString());
    }

    private static boolean symlinkSupported() {
        try {
            Path dir = Files.createTempDirectory("ca-symlink-probe");
            try {
                Path target = Files.writeString(dir.resolve("target.txt"), "x");
                Files.createSymbolicLink(dir.resolve("link.txt"), target);
                return Files.isSymbolicLink(dir.resolve("link.txt"));
            } finally {
                Files.deleteIfExists(dir.resolve("link.txt"));
                Files.deleteIfExists(dir.resolve("target.txt"));
                Files.deleteIfExists(dir);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }
}
