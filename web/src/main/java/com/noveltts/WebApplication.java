package com.noveltts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 主類別放在 com.noveltts 根 package：
 * Spring Boot 的 AutoConfigurationPackages 由 @SpringBootApplication 所在 package 決定，
 * JPA entity 與 repository 的掃描範圍依賴它（scanBasePackages 不影響 auto-configuration）。
 */
@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
