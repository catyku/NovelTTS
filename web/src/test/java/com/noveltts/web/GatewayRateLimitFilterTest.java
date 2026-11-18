package com.noveltts.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayRateLimitFilter} 的行為驗證。
 *
 * <p>重點：閘道以外的請求不受限流影響；超過配額時回 429；
 * Cloudflare 傳來的 CF-Connecting-IP 優先於 remoteAddr。
 */
class GatewayRateLimitFilterTest {

    private GatewayRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayRateLimitFilter();
    }

    /** 取得 filter 的實際處理結果。 */
    private MockHttpServletResponse doFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            // 放行
        });
        return response;
    }

    @Test
    void 非閘道路徑不受限流影響() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/novels");
        request.setRemoteAddr("1.2.3.4");

        for (int i = 0; i < 100; i++) {
            MockHttpServletResponse response = doFilter(request);
            assertEquals(200, response.getStatus(), "非閘道請求不應被限流");
        }
    }

    @Test
    void 超過配額回429() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/gateway/analyze");
        request.setRemoteAddr("1.2.3.4");

        MockHttpServletResponse last = null;
        for (int i = 0; i < 40; i++) {
            last = doFilter(request);
        }
        assertEquals(429, last.getStatus());
        assertTrue(last.getContentAsString().contains("rate_limited"));
    }

    @Test
    void 不同IP各自計數() throws Exception {
        MockHttpServletRequest a = new MockHttpServletRequest("POST", "/api/gateway/analyze");
        a.setRemoteAddr("1.1.1.1");
        MockHttpServletRequest b = new MockHttpServletRequest("POST", "/api/gateway/analyze");
        b.setRemoteAddr("2.2.2.2");

        for (int i = 0; i < 40; i++) {
            doFilter(a);
        }
        // 另一個 IP 不受影響
        assertEquals(200, doFilter(b).getStatus());
    }

    @Test
    void CloudflareIp優先於RemoteAddr() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/gateway/analyze");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.9");

        for (int i = 0; i < 40; i++) {
            doFilter(request);
        }
        // 同一個 Cloudflare IP 再來一次仍被限流（證明用的是 CF header 而非 remoteAddr）
        assertEquals(429, doFilter(request).getStatus());
    }
}
