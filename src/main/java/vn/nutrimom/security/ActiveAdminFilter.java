package vn.nutrimom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.ErrorCode;

/** Rechecks current account permissions after JWT authorization, once per admin request. */
public final class ActiveAdminFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public ActiveAdminFilter(UserRepository users) { this.users = users; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        String context = UriUtils.decode(request.getContextPath(), StandardCharsets.UTF_8);
        if (!context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        return "OPTIONS".equals(request.getMethod())
                || !(path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !users.isActiveAdmin(jwt.getToken().getSubject())) {
            SecurityErrorWriter.write(response, ErrorCode.FORBIDDEN,
                    "Tài khoản không còn quyền quản trị hoặc không hoạt động.", MDC.get("requestId"));
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
