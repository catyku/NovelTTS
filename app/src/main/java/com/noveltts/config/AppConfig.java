package com.noveltts.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(NovelTtsProperties.class)
@EnableAsync
public class AppConfig {

    /**
     * Spring Boot 4 中 RestClient 為預設的同步 HTTP client（WebClient 保留給反應式情境）。
     * AI 呼叫皆為阻塞式（分析、TTS），因此使用 RestClient。
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    {
                        setConnectTimeout(Duration.ofSeconds(15));
                        setReadTimeout(Duration.ofMinutes(5));
                    }
                });
    }

    /**
     * 主金鑰缺失時明確告警（不阻擋啟動，因為設定頁仍可操作）。
     */
    @Bean
    public MasterKeyChecker masterKeyChecker(NovelTtsProperties properties) {
        return new MasterKeyChecker(properties);
    }

    /**
     * 主金鑰檢查器。
     */
    public static class MasterKeyChecker {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(MasterKeyChecker.class);

        public MasterKeyChecker(NovelTtsProperties properties) {
            if (!properties.isMasterKeyConfigured()) {
                log.warn("NOVELTTS_MASTER_KEY 未設定或格式不正確（需為 base64 的 32 bytes）。"
                        + " API Key 將無法加密儲存。產生方式：openssl rand -base64 32");
                log.warn("注意：主金鑰必須跨重啟固定，否則已加密的 API Key 無法解密");
            } else {
                log.info("NOVELTTS_MASTER_KEY 已設定");
            }
        }
    }
}
