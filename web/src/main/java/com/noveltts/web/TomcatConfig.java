package com.noveltts.web;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat 連接器設定。
 *
 * <p><b>為什麼要調高 maxPartCount</b>
 *
 * <p>Tomcat 10.1 起把 {@code maxPartCount} 的預設值設為 50。
 * 這代表任何單一 multipart 請求最多只能有 50 個 part，
 * 超過就會回 413 —— 而且錯誤訊息是
 * {@code MaxUploadSizeExceededException: Maximum upload size exceeded}，
 * 看起來像是「大小超限」，實際上是「檔案數量超限」。
 *
 * <p>這對本專案有兩處影響：
 *
 * <ul>
 *   <li><b>合併整本 / 下載 ZIP</b>：原本一次送出所有音檔，
 *       114 段就直接被擋掉。改成分批上傳後，每批的檔案數
 *       仍需低於這個門檻。</li>
 *   <li><b>既有合併端點</b>：章節合併若超過 50 段也會被擋。</li>
 * </ul>
 *
 * <p>所以把上限調到 500，並在前端同時限制每批的檔案數。
 * 兩邊都要管 —— 只調伺服器會讓單一請求變大（更容易撞上
 * Cloudflare 的 100 秒逾時），只調前端則會在檔案數多的書上失效。
 */
@Configuration
public class TomcatConfig {

    /**
     * 單一 multipart 請求允許的最大 part 數。
     *
     * <p>Tomcat 預設 50。前端的分批上傳會把每批控制在 50 個以內，
     * 這個值留一點餘裕。
     */
    public static final int MAX_PART_COUNT = 500;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            // 用直接的 setter，不要用 setProperty ——
            // setProperty 走 IntrospectionUtils 反射，
            // 失敗時不會報錯，只是靜默地沒設到（踩過這個坑）。
            connector.setMaxPartCount(MAX_PART_COUNT);
            connector.setMaxParameterCount(5000);
            connector.setMaxPostSize(64 * 1024 * 1024);
            org.slf4j.LoggerFactory.getLogger(TomcatConfig.class)
                    .info("Tomcat 連接器設定：maxPartCount={} maxParameterCount={} maxPostSize={}",
                            connector.getMaxPartCount(), connector.getMaxParameterCount(),
                            connector.getMaxPostSize());
        });
    }
}
