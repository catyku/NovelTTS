package com.noveltts.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContentDisposition} 的驗證。
 *
 * <p>重點：非 ASCII 檔名不能直接放進 HTTP 標頭 —— Tomcat 會丟棄整個標頭，
 * 導致瀏覽器拿不到檔名。必須同時提供 ASCII fallback 與 RFC 5987 的 filename*。
 */
class ContentDispositionTest {

    @Test
    void 純Ascii檔名直接輸出() {
        assertEquals("attachment; filename=\"novel.zip\"",
                ContentDisposition.attachment("novel.zip"));
    }

    @Test
    void 中文檔名產生Rfc5987參數() {
        String header = ContentDisposition.attachment("測試小說.zip");

        assertTrue(header.startsWith("attachment; "), header);
        assertTrue(header.contains("filename*=UTF-8''"), header);
        // 中文以百分號編碼
        assertTrue(header.contains("%E6%B8%AC"), header);
    }

    @Test
    void 非Ascii時仍有AsciiFallback() {
        String header = ContentDisposition.attachment("測試小說.zip");

        assertTrue(header.contains("filename=\""), header);
        // fallback 內不得有非 ASCII
        String fallback = header.substring(header.indexOf("filename=\"") + 9,
                header.indexOf("\"", header.indexOf("filename=\"") + 9));
        assertTrue(fallback.chars().allMatch(c -> c < 128), fallback);
    }

    @Test
    void 整個標頭都是Ascii() {
        String header = ContentDisposition.attachment("第一章 夜歸.mp3");

        // Tomcat 只接受 ASCII 標頭 —— 整串都必須可編碼
        assertTrue(header.chars().allMatch(c -> c < 128), header);
    }

    @Test
    void 引號與換行被移除() {
        String header = ContentDisposition.attachment("a\"b\r\nc.zip");

        assertFalse(header.contains("\r"), header);
        assertFalse(header.contains("\n"), header);
    }

    @Test
    void 空白檔名有預設值() {
        assertTrue(ContentDisposition.attachment(null).contains("download"));
        assertTrue(ContentDisposition.attachment("   ").contains("download"));
    }

    @Test
    void Inline變體() {
        assertEquals("inline; filename=\"a.mp3\"", ContentDisposition.inline("a.mp3"));
    }
}
