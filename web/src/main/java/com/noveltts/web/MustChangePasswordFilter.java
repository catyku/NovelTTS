package com.noveltts.web;

import com.noveltts.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
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

    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        for (String allowed : ALLOWED_PATHS) {
            if (path.equals(allowed) || path.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }
}
