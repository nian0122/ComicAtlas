package com.comicatlas.common.dto;

import com.comicatlas.common.storage.InvalidRelativePathException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目录预览共享契约冻结测试：
 * 旧 Scan JSON 无新字段可读取（集合为空而非 null）；
 * 新 DTO 只输出正斜杠相对路径，不输出绝对路径；
 * 未知枚举在反序列化边界被拒绝为 typed error；
 * 非法相对路径（../、绝对、反斜杠）在构建边界被拒绝。
 */
class ScanContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void oldScanJson_readsWithSafeEmptyNewFields() throws Exception {
        String oldJson = "{"
                + "\"parentPath\":\"D:/scans/root\","
                + "\"total\":1,"
                + "\"items\":[{\"name\":\"comic1\",\"path\":\"D:/scans/root/comic1\",\"imageCount\":5}]"
                + "}";
        ScanResultDTO result = objectMapper.readValue(oldJson, ScanResultDTO.class);

        assertEquals("D:/scans/root", result.parentPath());
        assertEquals(1, result.total());
        assertEquals(1, result.items().size());
        ScanItemDTO item = result.items().get(0);
        assertEquals("comic1", item.name());
        assertNull(item.kind(), "旧 JSON 无 kind 时应为 null");
        assertNull(item.relativePath(), "旧 JSON 无 relativePath 时应为 null");
        assertNotNull(item.warnings(), "warnings 不应为 null");
        assertTrue(item.warnings().isEmpty(), "旧 JSON 缺 warnings 时应为 empty");
        assertNotNull(result.preview(), "preview 不应为 null");
        assertTrue(result.preview().isEmpty(), "旧 JSON 缺 preview 时应为 empty");
        assertNotNull(result.warnings(), "warnings 不应为 null");
        assertTrue(result.warnings().isEmpty(), "旧 JSON 缺 warnings 时应为 empty");
    }

    @Test
    void newScanDto_serializesForwardSlashRelativePathsWithoutAbsolutePath() throws Exception {
        ScanPreviewNodeDTO chapter = new ScanPreviewNodeDTO(
                "chapter1", ScanNodeKind.DIRECTORY, "comic1/chapter1", 5, List.of(), List.of());
        ScanPreviewNodeDTO comic = new ScanPreviewNodeDTO(
                "comic1", ScanNodeKind.COMIC, "comic1", 6,
                List.of(chapter),
                List.of(new ScanWarningDTO(ScanWarningCode.PATH_TOO_LONG,
                        ScanWarningSeverity.WARNING, "路径过长", "comic1")));
        ScanResultDTO result = new ScanResultDTO(
                "D:/scans/root", 1,
                List.of(new ScanItemDTO("comic1", "D:/scans/root/comic1", 6,
                        ScanNodeKind.COMIC, "comic1", List.of())),
                List.of(comic), List.of());

        String json = objectMapper.writeValueAsString(result);

        assertTrue(json.contains("comic1/chapter1"), "应输出正斜杠相对路径");
        assertTrue(json.contains("\"kind\":\"COMIC\""));
        assertTrue(json.contains("\"code\":\"PATH_TOO_LONG\""));
        assertTrue(json.contains("\"severity\":\"WARNING\""));
        assertTrue(json.contains("\"relativePath\":\"comic1\""), "应输出正斜杠相对路径");
        assertFalse(json.contains("\"relativePath\":\"D:/"), "相对路径不应输出绝对路径");
        assertFalse(json.contains("\"relativePath\":\"\\"), "相对路径不应输出反斜杠路径");
    }

    @Test
    void unknownEnumInJson_rejectedAsTypedError() {
        String json = "{\"code\":\"UNKNOWN_CODE\",\"severity\":\"WARNING\",\"message\":\"x\"}";
        JsonMappingException ex = assertThrows(JsonMappingException.class,
                () -> objectMapper.readValue(json, ScanWarningDTO.class));
        assertTrue(ex.getMessage().contains("UNKNOWN_CODE"), "应报告未知枚举值");
    }

    @Test
    void oldConstructors_preserveCompatibilityAndSafeDefaults() {
        ScanItemDTO item = new ScanItemDTO("comic1", "D:/scans/root/comic1", 5);
        assertNull(item.kind());
        assertNull(item.relativePath());
        assertNotNull(item.warnings());
        assertTrue(item.warnings().isEmpty());

        ScanResultDTO result = new ScanResultDTO("D:/scans/root", 0, List.of());
        assertNotNull(result.preview());
        assertTrue(result.preview().isEmpty());
        assertNotNull(result.warnings());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void invalidRelativePath_rejectedAsTypedError() {
        assertThrows(InvalidRelativePathException.class, () -> new ScanPreviewNodeDTO(
                "evil", ScanNodeKind.DIRECTORY, "../evil", 0, List.of(), List.of()));
        assertThrows(InvalidRelativePathException.class, () -> new ScanPreviewNodeDTO(
                "evil", ScanNodeKind.DIRECTORY, "C:/abs/path", 0, List.of(), List.of()));
        assertThrows(InvalidRelativePathException.class, () -> new ScanItemDTO(
                "comic1", "p", 1, ScanNodeKind.COMIC, "comic1\\001.jpg", List.of()));
        assertThrows(InvalidRelativePathException.class, () -> new ScanWarningDTO(
                ScanWarningCode.UNSAFE_PATH, ScanWarningSeverity.WARNING, "不安全路径", "/etc/passwd"));
    }
}
