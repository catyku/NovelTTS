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
import org.springframework.http.HttpMethod;
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
    private final GatewayRateLimitFilter gatewayRateLimitFilter;

    public SecurityConfig(MustChangePasswordFilter mustChangePasswordFilter,
                          AccountService accountService,
                          GatewayRateLimitFilter gatewayRateLimitFilter) {
        this.mustChangePasswordFilter = mustChangePasswordFilter;
        this.accountService = accountService;
        this.gatewayRateLimitFilter = gatewayRateLimitFilter;
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
                response.sendRedirect(request.getContextPath() + "/do-change-password");
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
                        // 無狀態 AI 閘道：使用者自帶 OpenRouter Key，不需要帳號。
                        // 閘道不讀寫任何使用者資料，呼叫次數由 GatewayRateLimitFilter 限制。
                        .requestMatchers("/api/gateway/**").permitAll()
                        // 訪客模式的頁面：資料在瀏覽器，頁面本身不含任何使用者資料。
                        .requestMatchers("/", "/novels", "/novels/**", "/groups", "/settings").permitAll()
                        // 使用說明頁與其截圖：公開的教學內容
                        .requestMatchers("/guide", "/docs/**").permitAll()
                        // 說明頁用的示範資料（產生截圖時用）
                        .requestMatchers("/guide-demo/**").permitAll()
                        // 唯讀的共用預設值（音色庫、模型定價）：訪客模式的設定頁需要它們
                        // 來提供建議值，但回應內容不涉及任何使用者資料。
                        .requestMatchers(HttpMethod.GET, "/api/settings/voices").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/settings/models").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/settings/pricing").permitAll()
                        // 其餘 API 仍需登入：那是「帳號模式」的後端，會讀寫資料庫。
                        .anyRequest().authenticated())
                // 閘道是無狀態 API：以 X-OpenRouter-Key 標頭驗證，不依賴 cookie，
                // 因此不需要 CSRF token。放行這條路徑的 CSRF 檢查。
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/gateway/**"))
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
                .addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class)
                // 閘道限流必須在 Security chain 之前，且排在 ForwardedHeaderFilter 之後，
                // 這樣才能讀到 Cloudflare 傳來的 CF-Connecting-IP。
                .addFilterBefore(gatewayRateLimitFilter, UsernamePasswordAuthenticationFilter.class);
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
