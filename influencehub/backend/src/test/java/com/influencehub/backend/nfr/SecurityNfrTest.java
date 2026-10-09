package com.influencehub.backend.nfr;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-SEC — "System ensures secure user authentication" and
 * "System restricts access based on user roles" (Report §1.1, §3.1 Tactic 1, §4.2.2.2).
 *
 * Quantified targets from the report (§4.2.2.2):
 *   - ALL restricted API endpoints enforce stateless JWT validation
 *   - max token lifespan 15 minutes
 *   - BCrypt work factor >= 12
 * plus the RBAC rules from §2.1.3-B (Authorization View).
 */
@DisplayName("NFR-SEC: Security & Role-Based Access Control")
class SecurityNfrTest extends NfrTestSupport {

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ── SEC-1 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-1: every restricted endpoint rejects anonymous requests with 401")
    void restrictedEndpointsRejectAnonymousRequests() {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();
        long campaignId = createCampaign(brand, "Anon-protection campaign");

        // method, path, body
        List<Object[]> endpoints = List.of(
                new Object[]{"GET", "/api/auth/users", null},
                new Object[]{"GET", "/api/creators", null},
                new Object[]{"GET", "/api/campaigns", null},
                new Object[]{"GET", "/api/campaigns/" + campaignId, null},
                new Object[]{"PUT", "/api/campaigns/" + campaignId + "/status", Map.of("status", "closed")},
                new Object[]{"POST", "/api/brand/profile/" + creator.id(), Map.of("brandName", "pwned")},
                new Object[]{"POST", "/api/campaigns", Map.of("title", "anon campaign")},
                new Object[]{"GET", "/api/brand/campaigns", null},
                new Object[]{"GET", "/api/brand/requests", null},
                new Object[]{"GET", "/api/influencer/profile", null},
                new Object[]{"GET", "/api/notifications", null},
                new Object[]{"GET", "/api/conversations", null},
                new Object[]{"PUT", "/api/settings/profile", Map.of("name", "x")},
                new Object[]{"DELETE", "/api/settings/account", null});

        List<String> violations = new ArrayList<>();
        for (Object[] e : endpoints) {
            Resp r = send((String) e[0], (String) e[1], e[2], null);
            if (r.status() != 401) violations.add(e[0] + " " + e[1] + " -> " + r.status());
        }

        assertThat(violations)
                .as("Endpoints that did NOT return 401 for an anonymous caller")
                .isEmpty();

        // Side-effect check: the anonymous status change must not have been applied.
        Resp campaign = get("/api/campaigns/" + campaignId, brand.token());
        assertThat(campaign.body()).doesNotContain("\"status\":\"closed\"");
    }

    // ── SEC-2 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-2: forged, tampered, expired and alg=none tokens are rejected")
    void invalidTokensAreRejected() {
        TestUser victim = registerBrand();
        Date now = new Date();

        String forged = Jwts.builder().subject(victim.email()).issuedAt(now)
                .expiration(new Date(now.getTime() + 600_000))
                .signWith(Keys.hmacShaKeyFor("attacker_controlled_secret_key_of_32+_bytes!!".getBytes()))
                .compact();

        String expired = Jwts.builder().subject(victim.email())
                .issuedAt(new Date(now.getTime() - 3_600_000))
                .expiration(new Date(now.getTime() - 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
                .compact();

        String b64 = "eyJhbGciOiJub25lIn0"; // {"alg":"none"}
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + victim.email() + "\",\"exp\":" + (now.getTime() / 1000 + 600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String algNone = b64 + "." + payload + ".";

        // Take a valid token of user A and swap the payload to impersonate the victim.
        TestUser attacker = registerInfluencer();
        String[] parts = attacker.token().split("\\.");
        String tampered = parts[0] + "." + payload + "." + parts[2];

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("forged", forged);
        tokens.put("expired", expired);
        tokens.put("alg=none", algNone);
        tokens.put("tampered", tampered);

        List<String> violations = new ArrayList<>();
        for (var t : tokens.entrySet()) {
            for (String path : List.of("/api/notifications", "/api/brand/campaigns", "/api/creators")) {
                Resp r = get(path, t.getValue());
                if (r.status() != 401) violations.add(t.getKey() + " token on " + path + " -> " + r.status());
            }
        }
        assertThat(violations).as("Invalid tokens that were accepted").isEmpty();
    }

    // ── SEC-3 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-3: issued JWTs expire within 15 minutes")
    void tokenLifetimeIsAtMost15Minutes() {
        TestUser user = registerBrand();
        Resp login = post("/api/auth/login", Map.of("email", user.email(), "password", user.password()), null);
        assertThat(login.status()).isEqualTo(200);

        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes())).build()
                .parseSignedClaims(login.json().get("token").asString()).getPayload();
        long lifetimeSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;

        assertThat(lifetimeSeconds)
                .as("JWT lifetime in seconds (report target: <= 900)")
                .isLessThanOrEqualTo(15 * 60);
    }

    // ── SEC-4 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-4: passwords are stored as BCrypt hashes with work factor >= 12")
    void passwordsUseBcryptCost12() {
        TestUser user = registerInfluencer();
        String hash = userRepository.findByEmail(user.email()).orElseThrow().getPassword();

        assertThat(hash).as("stored password must be a BCrypt hash").matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
        int cost = Integer.parseInt(hash.substring(4, 6));
        assertThat(cost).as("BCrypt work factor (report target: >= 12)").isGreaterThanOrEqualTo(12);
    }

    // ── SEC-5 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-5: no API response ever exposes a password hash")
    void noResponseLeaksPasswordHashes() {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();

        Map<String, Resp> responses = new LinkedHashMap<>();
        responses.put("GET /api/auth/users", get("/api/auth/users", brand.token()));
        Resp created = post("/api/campaigns", Map.of("title", "Leak check"), brand.token());
        responses.put("POST /api/campaigns", created);
        long campaignId = created.json().path("id").asLong();
        responses.put("PUT /api/campaigns/{id}/status",
                put("/api/campaigns/" + campaignId + "/status", Map.of("status", "paused"), brand.token()));

        // Unlock messaging legitimately, then open a conversation (returns the Conversation)
        long reqId = post("/api/requests", Map.of("creatorId", creator.id(), "description", "hi"), brand.token())
                .json().get("id").asLong();
        put("/api/requests/" + reqId + "/status", Map.of("status", "ACCEPTED"), creator.token());
        responses.put("POST /api/conversations",
                post("/api/conversations", Map.of("otherUserId", creator.id()), brand.token()));

        List<String> leaks = new ArrayList<>();
        responses.forEach((name, r) -> {
            if (r.body() != null && (r.body().contains("$2a$") || r.body().contains("\"password\"")))
                leaks.add(name + " (HTTP " + r.status() + ")");
        });
        assertThat(leaks).as("Responses containing a password / BCrypt hash").isEmpty();
    }

    // ── SEC-6 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-6: login does not reveal whether an account exists (no user enumeration)")
    void loginDoesNotEnumerateAccounts() {
        TestUser user = registerBrand();
        Resp unknownEmail = post("/api/auth/login",
                Map.of("email", uniqueEmail("nobody"), "password", "whatever1"), null);
        Resp wrongPassword = post("/api/auth/login",
                Map.of("email", user.email(), "password", "wrong-password"), null);

        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.body()).isEqualTo(wrongPassword.body());
    }

    // ── SEC-7 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-7: role & ownership rules are enforced server-side (RBAC)")
    void roleAndOwnershipRulesAreEnforced() {
        TestUser brandA = registerBrand();
        TestUser brandB = registerBrand();
        TestUser creator = registerInfluencer();
        long campaignOfA = createCampaign(brandA, "Brand A campaign");

        List<String> violations = new ArrayList<>();
        check(violations, "influencer creates a campaign",
                post("/api/campaigns", Map.of("title", "creator-made"), creator.token()), 403);
        check(violations, "influencer reads brand campaign dashboard",
                get("/api/brand/campaigns", creator.token()), 403);
        check(violations, "brand reads influencer-only profile endpoint",
                get("/api/influencer/profile", brandA.token()), 403);
        check(violations, "brand B changes status of brand A's campaign",
                put("/api/campaigns/" + campaignOfA + "/status", Map.of("status", "closed"), brandB.token()), 403);
        check(violations, "brand B overwrites another user's brand profile (IDOR)",
                post("/api/brand/profile/" + brandA.id(), Map.of("brandName", "hijacked"), brandB.token()), 403);
        check(violations, "brand sends a collaboration request to another brand",
                post("/api/requests", Map.of("creatorId", brandB.id(), "description", "x"), brandA.token()), 400);

        assertThat(violations).as("RBAC rules that were not enforced").isEmpty();
    }

    // ── SEC-8 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("SEC-8: messaging cannot be unlocked without the OTHER party accepting")
    void messagingGateCannotBeBypassed() {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();
        TestUser outsider = registerInfluencer();
        long campaignId = createCampaign(brand, "Gate campaign");

        // Influencer applies to the brand's campaign, then tries to accept their OWN application.
        long appId = post("/api/requests", Map.of("campaignId", campaignId, "message", "pick me"), creator.token())
                .json().get("id").asLong();
        Resp selfAccept = put("/api/requests/" + appId + "/status", Map.of("status", "ACCEPTED"), creator.token());
        Resp bypassChat = post("/api/conversations", Map.of("otherUserId", brand.id()), creator.token());

        assertThat(selfAccept.status()).as("initiator accepting their own request").isEqualTo(403);
        assertThat(bypassChat.status()).as("opening a chat without the brand's acceptance").isEqualTo(403);

        // Legitimate path: the brand (recipient) accepts -> messaging opens.
        assertThat(put("/api/requests/" + appId + "/status", Map.of("status", "ACCEPTED"), brand.token()).status())
                .isEqualTo(200);
        Resp conv = post("/api/conversations", Map.of("otherUserId", brand.id()), creator.token());
        assertThat(conv.status()).isEqualTo(200);

        // A third user can neither read nor post into that conversation.
        long convId = conv.json().get("id").asLong();
        assertThat(get("/api/conversations/" + convId + "/messages", outsider.token()).status()).isEqualTo(403);
        assertThat(post("/api/conversations/" + convId + "/messages", Map.of("text", "spam"), outsider.token()).status())
                .isEqualTo(403);
    }

    private static void check(List<String> violations, String scenario, Resp r, int expected) {
        if (r.status() != expected) violations.add(scenario + " -> expected " + expected + " but got " + r.status());
    }
}
