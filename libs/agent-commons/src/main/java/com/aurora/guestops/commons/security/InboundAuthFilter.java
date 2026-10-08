package com.aurora.guestops.commons.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects machine-to-machine endpoints (A2A JSON-RPC, MCP, registry writes). Agent Cards under
 * {@code /.well-known/} stay public, as A2A discovery expects; their contents are not secret.
 */
@Component
public class InboundAuthFilter extends OncePerRequestFilter {

    private final ServiceAuth serviceAuth;

    public InboundAuthFilter(ServiceAuth serviceAuth) {
        this.serviceAuth = serviceAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean machineEndpoint = path.startsWith("/a2a/") || path.startsWith("/mcp") || path.startsWith("/admin/")
                || (path.startsWith("/api/registry/") && !"GET".equals(request.getMethod()))
                || path.startsWith("/api/governance/") && !"GET".equals(request.getMethod())
                || path.startsWith("/api/audit") && !"GET".equals(request.getMethod());
        return !machineEndpoint || path.contains("/.well-known/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!serviceAuth.isValidInbound(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid service token\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
