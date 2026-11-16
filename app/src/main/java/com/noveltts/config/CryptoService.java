package com.noveltts.config;

import com.noveltts.domain.entity.AiProviderConfig;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 以 AES-256-GCM 加密後存 DB，主金鑰由環境變數提供。
 * 密文格式： base64( 12 bytes IV || ciphertext+tag )
 */
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final NovelTtsProperties properties;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(NovelTtsProperties properties) {
        this.properties = properties;
    }

    public boolean isMasterKeyConfigured() {
        String key = properties.getMasterKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(key.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        if (!isMasterKeyConfigured()) {
            throw new CryptoException(
                    "尚未設定 NOVELTTS_MASTER_KEY，無法加密 API Key。產生方式：openssl rand -base64 32");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new CryptoException("加密 API Key 失敗: " + e.getMessage(), e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CryptoException(
                    "解密 API Key 失敗: " + e.getMessage()
                            + "（主金鑰是否變更？NOVELTTS_MASTER_KEY 必須跨重啟固定）", e);
        }
    }

    public String maskKey(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        if (plain.length() <= 8) {
            return "****";
        }
        return plain.substring(0, 3) + "****" + plain.substring(plain.length() - 4);
    }

    /** 取得可用的明文 API Key。 */
    public String resolveApiKey(AiProviderConfig config) {
        return decrypt(config.getApiKeyEncrypted());
    }

    private SecretKeySpec secretKey() {
        byte[] raw = Base64.getDecoder().decode(properties.getMasterKey().trim());
        return new SecretKeySpec(raw, "AES");
    }
}
