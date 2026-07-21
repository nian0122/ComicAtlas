package com.comicatlas.api.importer.exception;
import lombok.Getter;
import java.util.List;
@Getter
public class HqDeletePreconditionException extends RuntimeException {
    private final List<String> details;
    public HqDeletePreconditionException(List<String> details) {
        super("以下页面 LQ 未就绪，无法删除 HQ");
        this.details = details;
    }
}
