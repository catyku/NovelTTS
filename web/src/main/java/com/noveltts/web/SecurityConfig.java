package com.noveltts.web;

import com.noveltts.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final MustChangePasswordFilter mustChangePasswordFilter;
    private final AccountService accountService;

    public SecurityConfig(MustChangePasswordFilter mustChangePasswordFilter,
                          AccountService accountService) {
        this.mustChangePasswordFilter = mustChangePasswordFilter;
        this.accountService = accountService;
    }

    /**
     * 登入成功後記錄最後登入時間。
     *
     * 若帳號仍需變更密碼，直接導向變更密碼頁，不讓使用者先進其他頁面再被 filter 擋。
     */
    private void loginSuccessHandler(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Authentication authentication) throws IOException {
        {
            String username = authentication.getName();
            accountService.recordLogin(username);
            if (accountService.mustChangePassword(username)) {
                response.sendRedirect(request.getContextPath() + "/account/password?required");
                return;
            }
            new org.springframework.security.web.DefaultRedirectStrategy()
                    .sendRedirect(request, response, "/");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 靜態資源與登入頁
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .requestMatchers("/login", "/do-login", "/error").permitAll()
                        // health check 不需登入
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/do-login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .successHandler(this::loginSuccessHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/do-logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                // 強制變更密碼：必須排在 Security filter chain 之後，
                // 才能讀到 SecurityContext 中的登入身分。
                .addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 讓容器信任反向代理轉發的 X-Forwarded-Proto / -Host / -For。
     *
     * Cloudflare Tunnel 會送 X-Forwarded-Proto: https，但沒有這個 filter 時
     * request.getScheme() 仍是 http，導致：
     * - login 成功後 redirect 到 http://...（HTTPS 降級）
     * - session cookie 的 secure 旗標設定錯誤
     *
     * 只信任來自 loopback 的請求（cloudflared 與本機直連都在本機），
     * 避免暴露在公開網路時被偽造 forwarded 標頭。
     */
    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        // 必須排在 Spring Security filter chain 之前（其 order 為 -100）。
        // 若用單純 @Bean Filter，Boot 會以 LOWEST_PRECEDENCE 註冊，
        // 導致 login redirect 產生當下 request 尚未包裝 forwarded 標頭，
        // redirect 會退回 http://（HTTPS 降級）。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

}
