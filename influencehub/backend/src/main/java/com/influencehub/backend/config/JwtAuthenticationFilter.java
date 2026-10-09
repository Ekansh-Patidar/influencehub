package com.influencehub.backend.config;

import com.influencehub.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Tactic 1 (Authenticate & Authorize Users): validates the Bearer JWT once, centrally,
 * for every request and populates the SecurityContext with ROLE_BRAND / ROLE_INFLUENCER.
 * Requests with a missing or invalid token continue anonymously and are rejected with
 * 401 by the SecurityConfig rules for any protected endpoint.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseClaims(header.substring(7));
                String email = claims.getSubject();
                String role = claims.get("role", String.class);
                if (role == null) {
                    // Tokens issued before the role claim existed: resolve the role once from the DB.
                    role = userRepository.findByEmail(email).map(u -> u.getRole()).orElse(null);
                }
                if (email != null && role != null) {
                    var auth = new UsernamePasswordAuthenticationToken(email, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // forged / expired / malformed token -> stay anonymous
            }
        }
        chain.doFilter(request, response);
    }
}
