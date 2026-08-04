package com.comicatlas.api.management.state;

/**
 * 非法状态迁移异常 — 当状态机拒绝迁移时抛出。
 * HTTP 层应映射为 409 Conflict，响应体包含 reasonCode 和 message。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final String entityType;
    private final String currentState;
    private final String targetState;
    private final String reasonCode;

    public IllegalStateTransitionException(String entityType, String currentState,
                                            String targetState, String reasonCode, String message) {
        super(message);
        this.entityType = entityType;
        this.currentState = currentState;
        this.targetState = targetState;
        this.reasonCode = reasonCode;
    }

    public String getEntityType() { return entityType; }
    public String getCurrentState() { return currentState; }
    public String getTargetState() { return targetState; }
    public String getReasonCode() { return reasonCode; }
}
