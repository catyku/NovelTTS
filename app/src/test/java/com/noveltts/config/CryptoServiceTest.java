package com.noveltts.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private CryptoService serviceWith(String key) {
        NovelTtsProperties props = new NovelTtsProperties();
        props.setMasterKey(key);
        return new CryptoService(props);
    }

    private static final String KEY =
            "P4fEJN1th4uLrNkf4ZxXthO2I2e6vdjd0MYmVYvk8+w=";

    @Test
    void 加密解密可還原() {
        CryptoService service = serviceWith(KEY);
        String plain = "sk-or-v1-abcdef1234567890";

        String encrypted = service.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(service.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void 相同明文每次密文不同() {
        CryptoService service = serviceWith(KEY);

        assertThat(service.encrypt("same"))
                .isNotEqualTo(service.encrypt("same"));
    }

    @Test
    void 換金鑰無法解密() {
        String otherKey = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=";
        String encrypted = serviceWith(KEY).encrypt("secret");

        assertThatThrownBy(() -> serviceWith(otherKey).decrypt(encrypted))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("主金鑰");
    }

    @Test
    void 未設定主金鑰時加密拋出明確錯誤() {
        CryptoService service = serviceWith("");

        assertThatThrownBy(() -> service.encrypt("secret"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("MASTER_KEY");
    }

    @Test
    void 遮蔽金鑰不洩漏完整內容() {
        CryptoService service = serviceWith(KEY);

        String masked = service.maskKey("sk-or-v1-abcdef1234567890");

        assertThat(masked).contains("****");
        assertThat(masked).doesNotContain("abcdef1234567890");
    }
}
