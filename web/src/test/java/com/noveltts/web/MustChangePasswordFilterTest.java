package com.noveltts.web;

import com.noveltts.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 強制變更密碼 filter 的路徑白名單測試。
 *
 * 背景：白名單使用 Ant 樣式（{@code /css/**}），若用 {@code String.startsWith}
 * 比對，{@code /css/app.css} 不會匹配，靜態資源會被 302 到變更密碼頁，
 * 瀏覽器把 HTML 當樣式表載入，頁面變成無樣式的原始排版。
 * 這個測試鎖住「必須用 AntPathMatcher」這個行為。
 */
class MustChangePasswordFilterTest {

    private final MustChangePasswordFilter filter =
            new MustChangePasswordFilter(mock(AccountService.class));

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }

    @Test
    @DisplayName("靜態資源必須放行，否則樣式表會被 302 成 HTML")
    void 靜態資源放行() {
        assertThat(filter.isAllowed(request("/css/app.css"))).isTrue();
        assertThat(filter.isAllowed(request("/js/common.js"))).isTrue();
        assertThat(filter.isAllowed(request("/images/logo.png"))).isTrue();
        assertThat(filter.isAllowed(request("/favicon.ico"))).isTrue();
    }

    @Test
    @DisplayName("變更密碼與登出端點必須放行")
    void 變更密碼相關端點放行() {
        assertThat(filter.isAllowed(request("/do-change-password"))).isTrue();
        assertThat(filter.isAllowed(request("/do-logout"))).isTrue();
        assertThat(filter.isAllowed(request("/error"))).isTrue();
    }

    @Test
    @DisplayName("一般頁面必須被擋（會導向變更密碼頁）")
    void 一般頁面被擋() {
        assertThat(filter.isAllowed(request("/"))).isFalse();
        assertThat(filter.isAllowed(request("/novels"))).isFalse();
        assertThat(filter.isAllowed(request("/settings"))).isFalse();
        assertThat(filter.isAllowed(request("/api/settings/cost"))).isFalse();
    }

    @Test
    @DisplayName("相近前綴不可誤放行")
    void 相近前綴不放行() {
        assertThat(filter.isAllowed(request("/cssx/app.css"))).isFalse();
        assertThat(filter.isAllowed(request("/do-change-password-evil"))).isFalse();
    }
}
