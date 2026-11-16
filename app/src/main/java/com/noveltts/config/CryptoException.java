package com.noveltts.config;

/**
 * API Key 加密/解密失敗。通常是主金鑰變更或未設定，屬於設定問題，重試無意義。
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
