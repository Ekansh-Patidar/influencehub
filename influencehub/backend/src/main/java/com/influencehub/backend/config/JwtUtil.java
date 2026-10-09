package com.influencehub.backend.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String SECRET;

    /** NFR-SEC (Report §4.2.2.2): maximum token lifespan of 15 minutes. */
    @Value("${jwt.expiration-minutes:15}")
    private long expirationMinutes;

    private javax.crypto.SecretKey getSignKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    public String generateToken(String email) {
        return generateToken(email, null);
    }

    /** The role claim lets the security filter authorize requests without a DB lookup. */
    public String generateToken(String email, String role) {
        JwtBuilder builder = Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMinutes * 60 * 1000))
                .signWith(getSignKey());
        if (role != null) builder.claim("role", role);
        return builder.compact();
    }

    /** Verifies signature + expiry and returns the claims; throws JwtException if invalid. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
