package com.comicatlas.common.storage;

/**
 * 非法相对路径 typed error：相对路径契约（只允许正斜杠分隔、禁止绝对路径、
 * 禁止反斜杠与目录穿越 ..）被违反时在构建/解析边界抛出。
 * 调用方可通过捕获该类型精确区分"路径契约违规"与其它异常。
 */
public class InvalidRelativePathException extends IllegalArgumentException {

    public InvalidRelativePathException(String message) {
        super(message);
    }

    public InvalidRelativePathException(String message, Throwable cause) {
        super(message, cause);
    }
}
