package com.noveltts.web;

import com.noveltts.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 強制變更密碼。
 *
 * 當帳號的 must_change_password 為 true（首次登入、使用預設密碼）時，
 * 除了變更密碼頁本身與登出外，一律導向變更密碼頁。
 *
 * 這是防止預設密碼 admin/admin 長期暴露在公開網路上的最後一道防線。
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /** 變更密碼頁與相關端點，不受此限制。 */
    private static final String[] ALLOWED_PATHS = {
            "/do-change-password",
            "/do-logout",
            "/css/**",
            "/js/**",
            "/images/**",
            "/favicon.ico",
            "/error"
    };

    /**
     * 路徑比對器。
     *
     * 必須用 AntPathMatcher 而非 String.startsWith —— 白名單裡的 {@code /css/**}
     * 是 Ant 樣式，不是字面前綴。用 startsWith 比對時 {@code /css/app.css}
     * 不會 startsWith {@code /css/**}，靜態資源會被擋下並 302 到變更密碼頁，
     * 瀏覽器拿到 HTML 當成樣式表，頁面就變成無樣式的原始排版。
     *
     * 與 SecurityConfig 的 requestMatchers 使用同一套語法，兩邊才會一致。
     */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final AccountService accountService;

    public MustChangePasswordFilter(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())
                && !isAllowed(request)) {
            if (accountService.mustChangePassword(auth.getName())) {
                response.sendRedirect(request.getContextPath() + "/do-change-password");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** package-private 供測試驗證白名單比對語法。 */
    boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        for (String allowed : ALLOWED_PATHS) {
            if (PATH_MATCHER.match(allowed, path)) {
                return true;
            }
        }
        return false;
    }
}
