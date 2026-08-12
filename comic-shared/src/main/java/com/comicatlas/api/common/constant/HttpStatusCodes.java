package com.comicatlas.api.common.constant;

/**
 * HTTP 状态码常量.
 * <p>
 * 参考 <a href="https://datatracker.ietf.org/doc/html/rfc7231">RFC 7231</a>
 * 和阿里 Java 开发手册【常量定义】规约，禁止魔法数字直接出现在业务代码中。
 */
public final class HttpStatusCodes {

    private HttpStatusCodes() {}

    // ======================== 2xx Success ========================

    /** 200 OK — 请求成功 */
    public static final int OK = 200;

    // ======================== 4xx Client Error ========================

    /** 400 Bad Request — 请求参数错误 */
    public static final int BAD_REQUEST = 400;

    /** 404 Not Found — 资源不存在 */
    public static final int NOT_FOUND = 404;

    /** 409 Conflict — 资源冲突（乐观锁/幂等键/唯一约束） */
    public static final int CONFLICT = 409;

    // ======================== 5xx Server Error ========================

    /** 500 Internal Server Error — 服务器内部错误 */
    public static final int INTERNAL_ERROR = 500;
}