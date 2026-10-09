package com.influencehub.backend.nfr;

import com.influencehub.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shared harness for the Non-Functional Requirement (NFR) test suite.
 *
 * Every test boots the real Spring Boot application on a random port (embedded Tomcat,
 * real security filter chain, real JPA) backed by an in-memory H2 database, and talks to
 * it over real HTTP — i.e. black-box tests of the deployed architecture, not mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class NfrTestSupport {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected UserRepository userRepository;

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    public record Resp(int status, String body) {
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    public record TestUser(long id, String email, String password, String token, String role) {
        public String bearer() {
            return token;
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected Resp send(String method, String path, Object body, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        b.method(method, publisher);
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }

    protected Resp get(String path, String token) { return send("GET", path, null, token); }
    protected Resp post(String path, Object body, String token) { return send("POST", path, body, token); }
    protected Resp put(String path, Object body, String token) { return send("PUT", path, body, token); }
    protected Resp delete(String path, String token) { return send("DELETE", path, null, token); }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    protected static String uniqueEmail(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8) + "@nfr.test";
    }

    protected TestUser registerBrand() {
        String email = uniqueEmail("brand");
        Resp r = post("/api/auth/register/brand", Map.of(
                "name", "Brand Owner",
                "email", email,
                "password", "Passw0rd!",
                "companyName", "Acme " + email.substring(6, 14),
                "industry", "Fashion"), null);
        if (r.status() != 200) throw new IllegalStateException("brand registration failed: " + r);
        return toUser(email, r);
    }

    protected TestUser registerInfluencer() {
        String email = uniqueEmail("creator");
        Resp r = post("/api/auth/register/influencer", Map.of(
                "name", "Creator " + email.substring(8, 16),
                "email", email,
                "password", "Passw0rd!",
                "handle", "@" + email.substring(8, 16),
                "niche", "Fashion",
                "followerCount", "120K"), null);
        if (r.status() != 200) throw new IllegalStateException("influencer registration failed: " + r);
        return toUser(email, r);
    }

    private TestUser toUser(String email, Resp r) {
        JsonNode n = r.json();
        long id = userRepository.findByEmail(email).orElseThrow().getId();
        return new TestUser(id, email, "Passw0rd!", n.get("token").asString(), n.get("role").asString());
    }

    protected long createCampaign(TestUser brand, String title) {
        Resp r = post("/api/campaigns", Map.of(
                "title", title,
                "industry", "Fashion",
                "description", "NFR test campaign",
                "budgetMax", 50000), brand.token());
        if (r.status() != 200) throw new IllegalStateException("campaign creation failed: " + r);
        return r.json().get("id").asLong();
    }

    // ── Concurrency helper ───────────────────────────────────────────────────

    /**
     * Fires {@code n} copies of {@code task} at the same instant (all threads wait on a
     * start gate) to maximise the chance of exposing race conditions.
     */
    protected static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
