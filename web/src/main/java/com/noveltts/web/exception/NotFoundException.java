package com.noveltts.web.exception;

/**
 * 找不到資源。對應 HTTP 404。
 *
 * 與 IllegalArgumentException（400）區分：
 * 「參數格式錯誤」是 400，「資源不存在」是 404，兩者對用戶的意義不同。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
