package com.noveltts.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 閘道端點的速率限制。
 *
 * <p>閘道是無狀態的：伺服器不保存使用者的 key，因此無法用帳號做配額。
 * 改以「來源 IP + 時間窗」限制呼叫次數，避免單一來源打爆伺服器或
 * 用同一把 key 大量消耗自己的 OpenRouter 額度。
 *
 * <p>以滑動視窗實作（不使用固定視窗，避免視窗邊界的瞬間倍增）。
 *
 * <p>注意：這是「防止單一來源失控」，不是嚴格的防護。
 * 分散多個 IP 的攻擊仍可能通過。真正的防護要放在 Cloudflare 層。
 */
@Component
public class GatewayRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);

    /** 允許的呼叫次數（每個時間窗內，每個 IP）。 */
    private static final int MAX_REQUESTS = 30;

    /** 時間窗長度（毫秒）。 */
    private static final long WINDOW_MS = 60_000L;

    /** 閾值以上才啟用計數，避免正常流量都進 map。 */
    private static final int CLEANUP_INTERVAL = 200;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/gateway/")) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientKey(request);
        Window window = windows.compute(key, (k, w) -> {
            long now = System.currentTimeMillis();
            if (w == null || now - w.startMs >= WINDOW_MS) {
                return new Window(now);
            }
            return w;
        });

        int count = window.count.incrementAndGet();
        if (count > MAX_REQUESTS) {
            log.warn("閘道速率限制觸發: ip={} count={}/{} per {}ms", key, count, MAX_REQUESTS, WINDOW_MS);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"請求過於頻繁，請稍後再試\"}");
            return;
        }

        if (requestCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            cleanup();
        }

        chain.doFilter(request, response);
    }

    /**
     * 取得來源 IP。
     *
     * <p>不信任 X-Forwarded-For 的第一段（可被偽造）。Cloudflare Tunnel 會設定
     * CF-Connecting-IP，這是可信的；本機直連則用 remoteAddr。
     */
    private String clientKey(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return "cf:" + cfIp;
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** 清除過期的時間窗，避免 map 無限增長。 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().startMs >= WINDOW_MS * 2);
    }

    /** 單一時間窗的計數。 */
    private static final class Window {
        private final long startMs;
        private final AtomicInteger count = new AtomicInteger();

        Window(long startMs) {
            this.startMs = startMs;
        }
    }
}
