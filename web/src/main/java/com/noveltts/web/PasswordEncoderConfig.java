package com.noveltts.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密碼編碼器。
 *
 * 獨立成類是為了斷開循環依賴：
 * SecurityConfig → MustChangePasswordFilter → AccountService → PasswordEncoder → SecurityConfig
 *
 * Spring Boot 4 的 Security 7 中，PasswordEncoder 需明確宣告為 bean；
 * 放在這裡讓 SecurityConfig 與 AccountService 都能注入而不形成循環。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
