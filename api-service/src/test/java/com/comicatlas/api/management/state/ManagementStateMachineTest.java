package com.comicatlas.api.management.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * 状态机迁移测试 — TDD：验证合法/非法迁移。
 * <p>
 * 三类非法迁移场景：
 * <ol>
 *   <li>终态→非终态（DELETED→READY）</li>
 *   <li>跨阶段跳跃（DRAFT→TRASHED）</li>
 *   <li>逆向迁移（READY→IMPORTING）</li>
 * </ol>
 * 非法迁移必须抛出 {@link IllegalStateTransitionException}，reasonCode 稳定且 DB version/status 不变。
 */
@DisplayName("ManagementStateMachine 状态迁移测试")
class ManagementStateMachineTest {

    // ======================== Comic 合法迁移 ========================

    @Nested
    @DisplayName("Comic 合法迁移")
    class ComicValidTransitions {

        static Stream<Arguments> validTransitions() {
            return Stream.of(
                Arguments.of("DRAFT",              "IMPORTING"),
                Arguments.of("DRAFT",              "TRASHING"),
                Arguments.of("IMPORTING",          "READY"),
                Arguments.of("IMPORTING",          "IMPORT_FAILED"),
                Arguments.of("IMPORT_FAILED",      "IMPORTING"),
                Arguments.of("IMPORT_FAILED",      "TRASHING"),
                Arguments.of("READY",              "TRASHING"),
                Arguments.of("READY",              "RECOVERY_REQUIRED"),
                Arguments.of("RECOVERY_REQUIRED",  "READY"),
                Arguments.of("RECOVERY_REQUIRED",  "TRASHING"),
                Arguments.of("DELETING",           "TRASHED"),
                Arguments.of("DELETING",           "RESTORING"),
                Arguments.of("TRASHING",           "TRASHED"),
                Arguments.of("TRASHING",           "READY"),
                Arguments.of("TRASHED",            "RESTORING"),
                Arguments.of("TRASHED",            "PURGING"),
                Arguments.of("RESTORING",          "READY"),
                Arguments.of("RESTORING",          "TRASHED"),
                Arguments.of("PURGING",            "DELETED")
            );
        }

        @ParameterizedTest
        @MethodSource("validTransitions")
        void shouldAllowTransition(String current, String target) {
            assertThatCode(() -> ManagementStateMachine.validateComicTransition(current, target))
                .doesNotThrowAnyException();
        }

        @Test
        void sameStateShouldBeNoop() {
            assertThatCode(() -> ManagementStateMachine.validateComicTransition("READY", "READY"))
                .doesNotThrowAnyException();
        }
    }

    // ======================== Comic 非法迁移 ========================

    @Nested
    @DisplayName("Comic 非法迁移")
    class ComicIllegalTransitions {

        static Stream<Arguments> illegalTransitions() {
            return Stream.of(
                // 终态→非终态
                Arguments.of("DELETED", "READY",    "DELETED_TO_READY_FORBIDDEN"),
                Arguments.of("DELETED", "IMPORTING", "DELETED_TO_IMPORTING_FORBIDDEN"),
                Arguments.of("DELETED", "TRASHED",   "DELETED_TO_TRASHED_FORBIDDEN"),
                // 跨阶段跳跃
                Arguments.of("DRAFT", "TRASHED",     "DRAFT_TO_TRASHED_FORBIDDEN"),
                Arguments.of("DRAFT", "READY",       "DRAFT_TO_READY_FORBIDDEN"),
                // 逆向迁移
                Arguments.of("READY", "IMPORTING",   "READY_TO_IMPORTING_FORBIDDEN"),
                Arguments.of("READY", "DRAFT",       "READY_TO_DRAFT_FORBIDDEN"),
                Arguments.of("TRASHED", "READY",     "TRASHED_TO_READY_FORBIDDEN"),
                Arguments.of("TRASHED", "DELETING",  "TRASHED_TO_DELETING_FORBIDDEN"),
                // 过渡态→非后继
                Arguments.of("IMPORTING", "DELETED", "IMPORTING_TO_DELETED_FORBIDDEN"),
                Arguments.of("DELETING", "READY",    "DELETING_TO_READY_FORBIDDEN")
            );
        }

        @ParameterizedTest(name = "{0} → {1} 应拒绝（reasonCode={2}）")
        @MethodSource("illegalTransitions")
        void shouldRejectTransition(String current, String target, String expectedReasonCode) {
            assertThatThrownBy(() -> ManagementStateMachine.validateComicTransition(current, target))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasFieldOrPropertyWithValue("reasonCode", expectedReasonCode)
                .hasFieldOrPropertyWithValue("entityType", "Comic")
                .hasFieldOrPropertyWithValue("currentState", current)
                .hasFieldOrPropertyWithValue("targetState", target);
        }

        @Test
        void nullCurrentShouldThrow() {
            assertThatThrownBy(() -> ManagementStateMachine.validateComicTransition(null, "READY"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "STATE_NULL");
        }

        @Test
        void nullTargetShouldThrow() {
            assertThatThrownBy(() -> ManagementStateMachine.validateComicTransition("READY", null))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "STATE_NULL");
        }
    }

    // ======================== Chapter 合法迁移 ========================

    @Nested
    @DisplayName("Chapter 合法迁移")
    class ChapterValidTransitions {

        static Stream<Arguments> validTransitions() {
            return Stream.of(
                Arguments.of("DRAFT",     "READY"),
                Arguments.of("DRAFT",     "TRASHING"),
                Arguments.of("READY",     "TRASHING"),
                Arguments.of("DELETING",  "TRASHED"),
                Arguments.of("DELETING",  "RESTORING"),
                Arguments.of("TRASHING",  "TRASHED"),
                Arguments.of("TRASHING",  "READY"),
                Arguments.of("TRASHED",   "RESTORING"),
                Arguments.of("TRASHED",   "PURGING"),
                Arguments.of("RESTORING", "READY"),
                Arguments.of("PURGING",   "DELETED")
            );
        }

        @ParameterizedTest
        @MethodSource("validTransitions")
        void shouldAllowTransition(String current, String target) {
            assertThatCode(() -> ManagementStateMachine.validateChapterTransition(current, target))
                .doesNotThrowAnyException();
        }
    }

    // ======================== Chapter 非法迁移 ========================

    @Nested
    @DisplayName("Chapter 非法迁移")
    class ChapterIllegalTransitions {

        @Test
        void deletedToReady() {
            assertThatThrownBy(() -> ManagementStateMachine.validateChapterTransition("DELETED", "READY"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "DELETED_TO_READY_FORBIDDEN");
        }

        @Test
        void readyToDraft() {
            assertThatThrownBy(() -> ManagementStateMachine.validateChapterTransition("READY", "DRAFT"))
                .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ======================== Media 合法迁移 ========================

    @Nested
    @DisplayName("Media 合法迁移")
    class MediaValidTransitions {

        @Test
        void stagingToReady() {
            assertThatCode(() -> ManagementStateMachine.validateMediaTransition("STAGING", "READY"))
                .doesNotThrowAnyException();
        }

        @Test
        void readyToTrashing() {
            assertThatCode(() -> ManagementStateMachine.validateMediaTransition("READY", "TRASHING"))
                .doesNotThrowAnyException();
        }
    }

    // ======================== Media 非法迁移 ========================

    @Nested
    @DisplayName("Media 非法迁移")
    class MediaIllegalTransitions {

        @Test
        void deletedToReady() {
            assertThatThrownBy(() -> ManagementStateMachine.validateMediaTransition("DELETED", "READY"))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "DELETED_TO_READY_FORBIDDEN");
        }

        @Test
        void stagingToTrashing() {
            assertThatThrownBy(() -> ManagementStateMachine.validateMediaTransition("STAGING", "TRASHING"))
                .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ======================== HQ 状态迁移 ========================

    @Nested
    @DisplayName("HQ 状态迁移")
    class HqTransitions {

        @Test
        void readyToDeleteQueued() {
            assertThatCode(() -> ManagementStateMachine.validateHqTransition("READY", "DELETE_QUEUED"))
                .doesNotThrowAnyException();
        }

        @Test
        void deletedToReady() {
            assertThatThrownBy(() -> ManagementStateMachine.validateHqTransition("DELETED", "READY"))
                .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ======================== LQ 状态迁移 ========================

    @Nested
    @DisplayName("LQ 状态迁移")
    class LqTransitions {

        @Test
        void notGeneratedToQueued() {
            assertThatCode(() -> ManagementStateMachine.validateLqTransition("NOT_GENERATED", "QUEUED"))
                .doesNotThrowAnyException();
        }

        @Test
        void queuedToGenerating() {
            assertThatCode(() -> ManagementStateMachine.validateLqTransition("QUEUED", "GENERATING"))
                .doesNotThrowAnyException();
        }

        @Test
        void readyToReady() {
            assertThatCode(() -> ManagementStateMachine.validateLqTransition("READY", "READY"))
                .doesNotThrowAnyException();
        }
    }

    // ======================== Transcode 状态迁移 ========================

    @Nested
    @DisplayName("Transcode 状态迁移")
    class TranscodeTransitions {

        @Test
        void notNeededToQueued() {
            assertThatCode(() -> ManagementStateMachine.validateTranscodeTransition("NOT_NEEDED", "QUEUED"))
                .doesNotThrowAnyException();
        }

        @Test
        void queuedToTranscoding() {
            assertThatCode(() -> ManagementStateMachine.validateTranscodeTransition("QUEUED", "TRANSCODING"))
                .doesNotThrowAnyException();
        }
    }

    // ======================== canTransition 方法 ========================

    @Nested
    @DisplayName("canTransition 检查方法")
    class CanTransitionChecks {

        @Test
        void shouldReturnTrueForValid() {
            assertThat(ManagementStateMachine.canTransitionComic("DRAFT", "IMPORTING")).isTrue();
            assertThat(ManagementStateMachine.canTransitionComic("READY", "TRASHING")).isTrue();
        }

        @Test
        void shouldReturnFalseForInvalid() {
            assertThat(ManagementStateMachine.canTransitionComic("DELETED", "READY")).isFalse();
            assertThat(ManagementStateMachine.canTransitionComic("READY", "DRAFT")).isFalse();
        }

        @Test
        void shouldReturnTrueForSameState() {
            assertThat(ManagementStateMachine.canTransitionComic("READY", "READY")).isTrue();
        }

        @Test
        void shouldReturnFalseForNull() {
            assertThat(ManagementStateMachine.canTransitionComic(null, "READY")).isFalse();
            assertThat(ManagementStateMachine.canTransitionComic("READY", null)).isFalse();
        }
    }
}
