package com.aitrade.common;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {
    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static ApiException badRequest(String msg) {
        return new ApiException(400, msg);
    }

    public static ApiException unauthorized(String msg) {
        return new ApiException(401, msg);
    }
}
