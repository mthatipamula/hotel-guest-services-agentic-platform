package com.aurora.guestops.orchestrator.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protection for a publicly reachable console:
 * <ul>
 *   <li>Admin actions (approve/reject, agent suspend/activate, publishing eval reports, clearing
 *       conversations) need the {@code X-Admin-Key} header when an admin key is configured.</li>
 *   <li>Chat requests are rate limited per client, on top of the agents' daily token budgets, so a public
 *       link cannot run up the Vertex AI bill.</li>
 * </ul>
 * With no admin key configured (local development), admin actions are open.
 */
@Component
public class PublicAccessFilter extends OncePerRequestFilter {

    private final String adminKey;
    private final int chatPerMinute;
    private final Cache<String, AtomicInteger> chatCounts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();

    public PublicAccessFilter(@Value("${guestops.console.admin-key:}") String adminKey,
                              @Value("${guestops.console.chat-requests-per-minute:6}") int chatPerMinute) {
        this.adminKey = adminKey;
        this.chatPerMinute = chatPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (isAdminAction(method, path) && !adminKey.isBlank() && !matches(request.getHeader("X-Admin-Key"))) {
            reject(response, 403, "admin_key_required", "This action needs the console admin key.");
            return;
        }
        if ("POST".equals(method) && "/api/chat".equals(path)) {
            int count = chatCounts.get(client(request), k -> new AtomicInteger()).incrementAndGet();
            if (count > chatPerMinute) {
                reject(response, 429, "rate_limited",
                        "Too many requests. The public demo allows " + chatPerMinute + " requests per minute.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static boolean isAdminAction(String method, String path) {
        return ("POST".equals(method) && (path.startsWith("/api/approvals/") || path.startsWith("/api/console/agents/")
                || path.equals("/api/evals")))
                || ("DELETE".equals(method) && path.startsWith("/api/conversations/"));
    }

    private boolean matches(String provided) {
        return provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                adminKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Cloud Run puts the real client address first in X-Forwarded-For. */
    private static String client(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }
}
