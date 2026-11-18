package com.noveltts.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Content-Disposition 標頭產生器。
 *
 * <p>HTTP 標頭只能承載 ASCII。中文檔名直接寫進去，Tomcat 會丟棄整個標頭
 * （log: {@code The HTTP response header [Content-Disposition] ... has been removed
 * from the response because it is invalid}），瀏覽器拿不到檔名，下載會失敗或變亂碼。
 *
 * <p>依 RFC 5987 產生兩個參數：
 * <ul>
 *   <li>{@code filename="..."} — ASCII fallback，非 ASCII 字元換成底線</li>
 *   <li>{@code filename*=UTF-8''...} — 百分號編碼的原始檔名，支援的瀏覽器優先採用</li>
 * </ul>
 */
public final class ContentDisposition {

    private ContentDisposition() {
    }

    /**
     * 產生 attachment 標頭。
     *
     * @param filename 原始檔名（可含中文）
     */
    public static String attachment(String filename) {
        return build("attachment", filename);
    }

    /**
     * 產生 inline 標頭。
     *
     * @param filename 原始檔名（可含中文）
     */
    public static String inline(String filename) {
        return build("inline", filename);
    }

    private static String build(String disposition, String filename) {
        String safe = filename == null ? "download" : filename;
        safe = safe.replace("\"", "").replace("\r", "").replace("\n", "").replace("\\", "_");
        if (safe.isBlank()) {
            safe = "download";
        }

        if (safe.chars().allMatch(c -> c < 128)) {
            return disposition + "; filename=\"" + safe + "\"";
        }

        // 非 ASCII：fallback 換成底線，完整名稱放 RFC 5987 的 filename*
        String fallback = safe.replaceAll("[^\\x20-\\x7e]", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return disposition + "; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
