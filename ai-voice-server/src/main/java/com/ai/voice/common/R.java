package com.ai.voice.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一 HTTP 响应封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应体")
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "状态码，0 成功")
    private int code;
    @Schema(description = "提示信息")
    private String msg;
    @Schema(description = "业务数据")
    private T data;

    public static <T> R<T> success(T data) {
        return new R<>(0, "success", data);
    }

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(-1, msg != null ? msg : "操作失败", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg != null ? msg : "操作失败", null);
    }
}
