package com.comicatlas.api.management.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_DELETE;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_EDIT;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_HQ_DELETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_IMPORT;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_LQ_GENERATE;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_METADATA_REFRESH;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_PURGE;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_READ;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_RECONCILE;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_RECOVER;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_RETRY_IMPORT;
import static com.comicatlas.api.management.policy.OperationPolicyService.OP_TRANSCODE;


/**
 * 操作策略服务测试 — 验证每个实体状态返回正确的允许/阻止操作。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>每个 comic/chapter/media 状态的操作矩阵</li>
 *   <li>过渡态（IMPORTING/DELETING/RESTORING）阻止所有操作</li>
 *   <li>终态 DELETED 阻止所有操作</li>
 *   <li>LQ non-READY 时阻止 HQ_DELETE 请求</li>
 *   <li>DRAFT/TRASHED 不出现在阅读列表（通过 blocked 验证）</li>
 * </ul>
 */
@DisplayName("AllowedOperationServiceTest 操作策略测试")
class AllowedOperationServiceTest {

    private final OperationPolicyService service = new OperationPolicyService();

    // ======================== Comic 操作矩阵 ========================

    @Nested
    @DisplayName("Comic 操作矩阵")
    class ComicOperations {

        @Test
        void draftShouldAllowImportEditDelete() {
            AllowedOperations ops = service.forComic("DRAFT");
            assertThat(ops.allowed()).contains(OP_IMPORT, OP_EDIT, OP_DELETE);
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void importingShouldBlockAll() {
            AllowedOperations ops = service.forComic("IMPORTING");
            assertThat(ops.allowed()).isEmpty();
            assertThat(ops.blockedReasons()).isNotEmpty();
            assertThat(ops.blockedReasons().values()).anyMatch(r -> r.contains("导入中"));
        }

        @Test
        void importFailedShouldAllowRetryDeleteOnly() {
            AllowedOperations ops = service.forComic("IMPORT_FAILED");
            assertThat(ops.allowed()).contains(OP_RETRY_IMPORT, OP_DELETE);
            assertThat(ops.isAllowed(OP_EDIT)).isFalse();
        }

        @Test
        void readyShouldAllowOperationsIncludingMetadataRefresh() {
            AllowedOperations ops = service.forComic("READY");
            assertThat(ops.allowed()).contains(
                OP_READ, OP_EDIT, OP_DELETE, OP_LQ_GENERATE, OP_HQ_DELETE, OP_METADATA_REFRESH);
            assertThat(ops.isAllowed(OP_METADATA_REFRESH)).isTrue();
            assertThat(ops.blockedReasons()).doesNotContainKey(OP_METADATA_REFRESH);
        }

        @Test
        void nonReadyShouldBlockMetadataRefresh() {
            assertThat(service.forComic("IMPORTING").isAllowed(OP_METADATA_REFRESH)).isFalse();
            assertThat(service.forComic("IMPORT_FAILED").isAllowed(OP_METADATA_REFRESH)).isFalse();
            assertThat(service.forComic("TRASHED").isAllowed(OP_METADATA_REFRESH)).isFalse();
            assertThat(service.forComic("DELETING").isAllowed(OP_METADATA_REFRESH)).isFalse();
            assertThat(service.forComic("RECOVERY_REQUIRED").isAllowed(OP_METADATA_REFRESH)).isFalse();
        }

        @Test
        void recoveryRequiredShouldAllowRecoverDelete() {
            AllowedOperations ops = service.forComic("RECOVERY_REQUIRED");
            assertThat(ops.allowed()).contains(OP_RECOVER, OP_DELETE);
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void deletingShouldBlockAll() {
            AllowedOperations ops = service.forComic("DELETING");
            assertThat(ops.allowed()).isEmpty();
            assertThat(ops.blockedReasons()).containsKey("*");
        }

        @Test
        void trashingShouldAllowReconcileOnly() {
            AllowedOperations ops = service.forComic("TRASHING");
            assertThat(ops.allowed()).containsExactly(OP_RECONCILE);
            assertThat(ops.isAllowed(OP_READ)).isFalse();
            assertThat(ops.isAllowed(OP_DELETE)).isFalse();
            assertThat(ops.blockedReasons()).containsKey("*");
        }

        @Test
        void trashedShouldAllowRecoverPurge() {
            AllowedOperations ops = service.forComic("TRASHED");
            assertThat(ops.allowed()).contains(OP_RECOVER, OP_PURGE);
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void restoringShouldBlockAll() {
            AllowedOperations ops = service.forComic("RESTORING");
            assertThat(ops.allowed()).isEmpty();
        }

        @Test
        void purgingShouldBlockAll() {
            AllowedOperations ops = service.forComic("PURGING");
            assertThat(ops.allowed()).isEmpty();
        }

        @Test
        void deletedShouldBlockAll() {
            AllowedOperations ops = service.forComic("DELETED");
            assertThat(ops.allowed()).isEmpty();
            assertThat(ops.blockedReasons()).containsKey("*");
        }

        @Test
        void unknownStatusShouldBlockAll() {
            AllowedOperations ops = service.forComic("UNKNOWN");
            assertThat(ops.allowed()).isEmpty();
            assertThat(ops.blockedReasons()).containsKey("*");
        }
    }

    // ======================== Chapter 操作矩阵 ========================

    @Nested
    @DisplayName("Chapter 操作矩阵")
    class ChapterOperations {

        @Test
        void draftShouldAllowEditDelete() {
            AllowedOperations ops = service.forChapter("DRAFT");
            assertThat(ops.allowed()).contains(OP_EDIT, OP_DELETE);
        }

        @Test
        void readyShouldAllowAllOperations() {
            AllowedOperations ops = service.forChapter("READY");
            assertThat(ops.allowed()).contains(
                OP_READ, OP_EDIT, OP_DELETE, OP_LQ_GENERATE, OP_HQ_DELETE);
        }

        @Test
        void trashedShouldAllowRecoverPurge() {
            AllowedOperations ops = service.forChapter("TRASHED");
            assertThat(ops.allowed()).contains(OP_RECOVER, OP_PURGE);
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void deletedShouldBlockAll() {
            AllowedOperations ops = service.forChapter("DELETED");
            assertThat(ops.allowed()).isEmpty();
        }
    }

    // ======================== Media 操作矩阵 ========================

    @Nested
    @DisplayName("Media 操作矩阵")
    class MediaOperations {

        @Test
        void stagingShouldBlockAll() {
            AllowedOperations ops = service.forMedia("STAGING");
            assertThat(ops.allowed()).isEmpty();
        }

        @Test
        void readyShouldAllowAllOperations() {
            AllowedOperations ops = service.forMedia("READY");
            assertThat(ops.allowed()).contains(
                OP_READ, OP_DELETE, OP_LQ_GENERATE, OP_HQ_DELETE, OP_TRANSCODE);
        }

        @Test
        void deletedShouldBlockAll() {
            AllowedOperations ops = service.forMedia("DELETED");
            assertThat(ops.allowed()).isEmpty();
        }
    }

    // ======================== 复合操作判断 ========================

    @Nested
    @DisplayName("HQ_DELETE 前置检查")
    class HqDeletePreconditions {

        @Test
        void readyHqShouldAllowHqDelete() {
            assertThat(service.canRequestHqDelete("READY")).isTrue();
        }

        @Test
        void missingHqShouldAllowHqDelete() {
            assertThat(service.canRequestHqDelete("MISSING")).isTrue();
        }

        @Test
        void deletedHqShouldNotAllowHqDelete() {
            assertThat(service.canRequestHqDelete("DELETED")).isFalse();
        }

        @Test
        void pendingHqShouldNotAllowHqDelete() {
            assertThat(service.canRequestHqDelete("PENDING")).isFalse();
        }
    }

    @Nested
    @DisplayName("LQ_GENERATE 前置检查")
    class LqGeneratePreconditions {

        @Test
        void notGeneratedShouldAllow() {
            assertThat(service.canRequestLqGenerate("NOT_GENERATED")).isTrue();
        }

        @Test
        void failedShouldAllow() {
            assertThat(service.canRequestLqGenerate("FAILED")).isTrue();
        }

        @Test
        void missingShouldAllow() {
            assertThat(service.canRequestLqGenerate("MISSING")).isTrue();
        }

        @Test
        void readyShouldReject() {
            assertThat(service.canRequestLqGenerate("READY")).isFalse();
        }

        @Test
        void generatingShouldReject() {
            assertThat(service.canRequestLqGenerate("GENERATING")).isFalse();
        }
    }

    @Nested
    @DisplayName("TRANSCODE 前置检查")
    class TranscodePreconditions {

        @Test
        void notNeededShouldAllow() {
            assertThat(service.canRequestTranscode("NOT_NEEDED")).isTrue();
        }

        @Test
        void failedShouldAllow() {
            assertThat(service.canRequestTranscode("FAILED")).isTrue();
        }

        @Test
        void readyShouldReject() {
            assertThat(service.canRequestTranscode("READY")).isFalse();
        }
    }

    // ======================== AllowedOperations DTO ========================

    @Nested
    @DisplayName("AllowedOperations DTO")
    class AllowedOperationsDto {

        @Test
        void ofShouldReturnUnmodifiable() {
            AllowedOperations ops = AllowedOperations.of(
                java.util.Set.of("a", "b"),
                java.util.Map.of("c", "reason"));
            assertThat(ops.allowed()).containsExactlyInAnyOrder("a", "b");
            assertThat(ops.blockedReasons()).containsEntry("c", "reason");
            assertThatThrownBy(() -> ops.allowed().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void noneShouldReturnEmptyWithWildcard() {
            AllowedOperations ops = AllowedOperations.none("全部禁止");
            assertThat(ops.allowed()).isEmpty();
            assertThat(ops.blockedReasons()).containsEntry("*", "全部禁止");
        }

        @Test
        void onlyShouldReturnSpecified() {
            AllowedOperations ops = AllowedOperations.only(java.util.Set.of("x", "y"));
            assertThat(ops.allowed()).containsExactlyInAnyOrder("x", "y");
            assertThat(ops.blockedReasons()).isEmpty();
        }

        @Test
        void isAllowedShouldWork() {
            AllowedOperations ops = AllowedOperations.only(java.util.Set.of("READ"));
            assertThat(ops.isAllowed("READ")).isTrue();
            assertThat(ops.isAllowed("DELETE")).isFalse();
        }
    }

    // ======================== 阅读列表可见性 ========================

    @Nested
    @DisplayName("阅读列表不可见状态验证")
    class ReaderVisibility {

        @Test
        void draftShouldNotBeReadable() {
            AllowedOperations ops = service.forComic("DRAFT");
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void trashedShouldNotBeReadable() {
            AllowedOperations ops = service.forComic("TRASHED");
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void deletedShouldNotBeReadable() {
            AllowedOperations ops = service.forComic("DELETED");
            assertThat(ops.isAllowed(OP_READ)).isFalse();
        }

        @Test
        void readyShouldBeReadable() {
            AllowedOperations ops = service.forComic("READY");
            assertThat(ops.isAllowed(OP_READ)).isTrue();
        }
    }
}
