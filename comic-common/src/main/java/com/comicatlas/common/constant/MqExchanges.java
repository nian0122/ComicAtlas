package com.comicatlas.common.constant;

/**
 * RabbitMQ exchange 名常量（契约与 AGENTS.md「RABBITMQ 事件命名规范（冻结）」一致）。
 * <p>
 * api-service 与 worker-service 共享同一拓扑定义，业务代码禁止硬编码 exchange 名。
 */
public final class MqExchanges {
    private MqExchanges() {
    }

    public static final String IMPORT = "comic.import";
    public static final String IMPORT_DLX = "comic.import.dlx";
    public static final String IMAGE = "comic.image";
    public static final String IMAGE_DLX = "comic.image.dlx";
    public static final String TASK = "comic.task";
    public static final String DELETE = "comic.delete";
    public static final String DELETE_DLX = "comic.delete.dlx";
    public static final String EXPORT = "comic.export";
    public static final String EXPORT_DLX = "comic.export.dlx";
    public static final String VIDEO = "comic.video";
    public static final String VIDEO_DLX = "comic.video.dlx";
    public static final String RECOVERY = "comic.recovery";
    public static final String RECOVERY_DLX = "comic.recovery.dlx";
    public static final String SCAN = "comic.scan";
    public static final String SCAN_DLX = "comic.scan.dlx";
    public static final String MANAGEMENT = "comic.management";
    public static final String MANAGEMENT_DLX = "comic.management.dlx";
}
