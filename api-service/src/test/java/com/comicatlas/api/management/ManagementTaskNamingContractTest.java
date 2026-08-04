package com.comicatlas.api.management;

import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理任务批量字段命名契约测试。
 * <p>
 * 锁定：Java 内部字段为 {@code batch}（Lombok 生成 getBatch/setBatch），
 * DB 列显式映射 {@code is_batch}，REST JSON 键保持 {@code isBatch}。
 */
class ManagementTaskNamingContractTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    // ======================== Lombok 访问器 ========================

    @Test
    @DisplayName("实体生成 getBatch/setBatch，不再生成旧访问器")
    void entity_generatesNewAccessors_only() throws Exception {
        Method getBatch = ManagementTask.class.getMethod("getBatch");
        Method setBatch = ManagementTask.class.getMethod("setBatch", Boolean.class);
        assertEquals(Boolean.class, getBatch.getReturnType());

        assertThrows(NoSuchMethodException.class,
                () -> ManagementTask.class.getMethod("getIsBatch"));
        assertThrows(NoSuchMethodException.class,
                () -> ManagementTask.class.getMethod("setIsBatch", Boolean.class));
    }

    @Test
    @DisplayName("响应 DTO 生成 getBatch/setBatch，不再生成旧访问器")
    void dto_generatesNewAccessors_only() throws Exception {
        Method getBatch = ManagementTaskResponse.class.getMethod("getBatch");
        Method setBatch = ManagementTaskResponse.class.getMethod("setBatch", Boolean.class);
        assertEquals(Boolean.class, getBatch.getReturnType());

        assertThrows(NoSuchMethodException.class,
                () -> ManagementTaskResponse.class.getMethod("getIsBatch"));
        assertThrows(NoSuchMethodException.class,
                () -> ManagementTaskResponse.class.getMethod("setIsBatch", Boolean.class));
    }

    // ======================== MyBatis 列映射 ========================

    @Test
    @DisplayName("实体 batch 字段显式映射到 is_batch 列")
    void entity_batchField_mapsToIsBatchColumn() throws Exception {
        Field batch = ManagementTask.class.getDeclaredField("batch");
        TableField tableField = batch.getAnnotation(TableField.class);
        assertTrue(tableField != null && "is_batch".equals(tableField.value()),
                "ManagementTask.batch 必须通过 @TableField 显式映射到 is_batch");
    }

    // ======================== Jackson JSON 契约 ========================

    @Test
    @DisplayName("序列化 true 时只输出 isBatch 键，不含 batch")
    void serialize_true_containsOnlyIsBatch() throws Exception {
        ManagementTaskResponse resp = new ManagementTaskResponse();
        resp.setBatch(true);

        String json = objectMapper.writeValueAsString(resp);
        Map<?, ?> map = objectMapper.readValue(json, Map.class);

        assertTrue(map.containsKey("isBatch"));
        assertEquals(Boolean.TRUE, map.get("isBatch"));
        assertFalse(map.containsKey("batch"), "JSON 不得出现 batch 键");
    }

    @Test
    @DisplayName("序列化 false 时只输出 isBatch 键")
    void serialize_false_containsOnlyIsBatch() throws Exception {
        ManagementTaskResponse resp = new ManagementTaskResponse();
        resp.setBatch(false);

        String json = objectMapper.writeValueAsString(resp);
        Map<?, ?> map = objectMapper.readValue(json, Map.class);

        assertTrue(map.containsKey("isBatch"));
        assertEquals(Boolean.FALSE, map.get("isBatch"));
        assertFalse(map.containsKey("batch"), "JSON 不得出现 batch 键");
    }

    @Test
    @DisplayName("反序列化 isBatch 键可回填 batch 字段")
    void deserialize_isBatch_backfillsBatch() throws Exception {
        ManagementTaskResponse resp =
                objectMapper.readValue("{\"isBatch\": true}", ManagementTaskResponse.class);
        assertEquals(Boolean.TRUE, resp.getBatch());

        ManagementTaskResponse respFalse =
                objectMapper.readValue("{\"isBatch\": false}", ManagementTaskResponse.class);
        assertEquals(Boolean.FALSE, respFalse.getBatch());
    }

    @Test
    @DisplayName("缺省 isBatch 键时 batch 为 null")
    void deserialize_missingIsBatch_batchIsNull() throws Exception {
        ManagementTaskResponse resp =
                objectMapper.readValue("{}", ManagementTaskResponse.class);
        assertNull(resp.getBatch());
    }
}
