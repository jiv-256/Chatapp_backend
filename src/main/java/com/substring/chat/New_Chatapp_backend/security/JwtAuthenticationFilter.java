package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final UserTokenService userTokenService;
    private final UserRepo userRepo;

    @Autowired
    public JwtAuthenticationFilter(UserTokenService userTokenService, @Autowired(required = false) UserRepo userRepo) {
        this.userTokenService = userTokenService;
        this.userRepo = userRepo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI();
        return "/api/v1/auth/login".equals(path) ||
               "/api/v1/auth/register".equals(path) ||
               "/api/v1/auth/logout".equals(path) ||
               path.startsWith("/chat/") ||
               "/error".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = getJwtFromRequest(request);
            if (StringUtils.hasText(token)) {
                UserTokenClaims claims = userTokenService.validateToken(token);
                if (claims != null && StringUtils.hasText(claims.getEmail())) {
                    // Build principal directly from validated, tamper-proof cryptographic claims
                    // avoiding redundant database round-trips on every request
                    UserPrincipal principal = UserPrincipal.create(claims);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String customTokenHeader = request.getHeader("X-Auth-Token");
        if (StringUtils.hasText(customTokenHeader)) {
            return customTokenHeader.trim();
        }

        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && (bearerToken.startsWith("Bearer ") || bearerToken.startsWith("bearer "))) {
            return bearerToken.substring(7).trim();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("auth_token".equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue().trim();
                }
            }
        }

        return null;
    }
}
