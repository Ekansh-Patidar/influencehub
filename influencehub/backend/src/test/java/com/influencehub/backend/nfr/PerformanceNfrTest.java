package com.influencehub.backend.nfr;

import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.brand.model.Campaign;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.brand.repository.CampaignRepository;
import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.model.CollaborationRequest;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import com.influencehub.backend.repository.NotificationRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-PERF — Report §4.2.2.1: "95% of all read operations (e.g. searching for campaigns,
 * viewing influencer profiles) must return a response in under 200 ms" and §1.1
 * "System supports scalability for growing users".
 *
 * Load model (scaled down from the report's 1,000 users to what one laptop can generate
 * without the load generator itself becoming the bottleneck):
 *   100 concurrent virtual users x 20 requests each, 50-150 ms think time,
 *   against a seeded dataset of 500 creators, 20 brands, 200 campaigns, 2,000 requests.
 *
 * Run against the live dev server instead of the in-process one with:
 *   -Dperf.baseUrl=http://localhost:8082 [-Dperf.brandToken=... -Dperf.influencerToken=...]
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nfr.suite=performance") // own context => own isolated in-memory DB
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("NFR-PERF: Performance & scalability")
class PerformanceNfrTest extends NfrTestSupport {

    static final int VIRTUAL_USERS = 100;
    static final int REQUESTS_PER_USER = 20;
    static final long P95_TARGET_MS = 200;
    static final int MAX_SQL_PER_REQUEST = 10;

    static final int INFLUENCERS = 500, BRANDS = 20, CAMPAIGNS = 200, REQUESTS = 2000;

    static final String LIVE_URL = System.getProperty("perf.baseUrl");

    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BrandProfileRepository brandProfileRepository;
    @Autowired private InfluencerProfileRepository influencerProfileRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CollaborationRequestRepository requestRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private EntityManagerFactory emf;

    private String brandToken, influencerToken;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> creatorProfileIds = new ArrayList<>();

    @Override
    protected String baseUrl() {
        return LIVE_URL != null ? LIVE_URL : super.baseUrl();
    }

    // ── Seed ─────────────────────────────────────────────────────────────────
    @BeforeAll
    void seed() {
        if (LIVE_URL != null) {
            brandToken = System.getProperty("perf.brandToken");
            influencerToken = System.getProperty("perf.influencerToken");
            String tok = brandToken != null ? brandToken : influencerToken;
            get("/api/campaigns?size=50", tok).json().path("campaigns")
                    .forEach(c -> campaignIds.add(c.get("id").asLong()));
            get("/api/creators?size=50", tok).json().path("creators")
                    .forEach(c -> creatorProfileIds.add(c.get("id").asLong()));
            return;
        }
        Random rnd = new Random(42);
        String hash = passwordEncoder.encode("Passw0rd!");
        String avatar = "data:image/jpeg;base64," + "A".repeat(20_000); // ~20 KB, like a real upload

        List<User> brands = new ArrayList<>();
        for (int i = 0; i < BRANDS; i++) {
            User u = user("perf_brand_" + i + "@nfr.test", "brand", hash);
            brands.add(u);
            BrandProfile bp = new BrandProfile();
            bp.setBrandName("PerfBrand " + i);
            bp.setIndustry("Fashion");
            bp.setUser(u);
            brandProfileRepository.save(bp);
        }

        String[] niches = {"Fashion", "Tech", "Food", "Travel", "Fitness"};
        List<User> creators = new ArrayList<>();
        for (int i = 0; i < INFLUENCERS; i++) {
            User u = user("perf_creator_" + i + "@nfr.test", "influencer", hash);
            if (i % 4 == 0) { u.setAvatar(avatar); userRepository.save(u); }
            creators.add(u);
            InfluencerProfile p = new InfluencerProfile();
            p.setUser(u);
            p.setHandle("@perf" + i);
            p.setNiche(niches[i % niches.length]);
            p.setFollowerCount((10 + i) + "K");
            p.setEngagementRate("4.2%");
            p.setBio("Seeded creator " + i);
            creatorProfileIds.add(influencerProfileRepository.save(p).getId());
        }

        List<Campaign> campaigns = new ArrayList<>();
        for (int i = 0; i < CAMPAIGNS; i++) {
            Campaign c = new Campaign();
            c.setBrand(brands.get(i % BRANDS));
            c.setTitle("Perf campaign " + i);
            c.setIndustry(niches[i % niches.length]);
            c.setDescription("Seeded campaign " + i);
            c.setContentTypes(new ArrayList<>(List.of("Reel", "Story")));
            c.setPlatforms(new ArrayList<>(List.of("Instagram", "YouTube")));
            c.setBudgetMax(10_000d + i);
            c.setDraftDeadline(LocalDate.now().plusDays(30));
            campaigns.add(c);
        }
        campaignRepository.saveAll(campaigns).forEach(c -> campaignIds.add(c.getId()));

        String[] statuses = {"PENDING", "ACCEPTED", "REJECTED"};
        List<CollaborationRequest> reqs = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            Campaign c = campaigns.get(rnd.nextInt(CAMPAIGNS));
            CollaborationRequest r = new CollaborationRequest();
            r.setBrand(i % 4 == 0 ? brands.get(0) : c.getBrand());  // brand 0 gets a heavy inbox
            r.setCreator(i % 5 == 0 ? creators.get(0) : creators.get(rnd.nextInt(INFLUENCERS)));
            r.setCampaign(c);
            r.setInitiatedBy(i % 2 == 0 ? "INFLUENCER" : "BRAND");
            r.setStatus(statuses[i % 3]);
            r.setMessage("Seeded request " + i);
            reqs.add(r);
        }
        requestRepository.saveAll(reqs);

        brandToken = post("/api/auth/login", Map.of("email", "perf_brand_0@nfr.test", "password", "Passw0rd!"), null)
                .json().get("token").asString();
        influencerToken = post("/api/auth/login", Map.of("email", "perf_creator_0@nfr.test", "password", "Passw0rd!"), null)
                .json().get("token").asString();
    }

    private User user(String email, String role, String hash) {
        User u = new User();
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEmail(email);
        u.setPassword(hash);
        u.setRole(role);
        return userRepository.save(u);
    }

    /** The read operations from the report's NFR ("searching campaigns, viewing influencer profiles") + dashboards. */
    private Map<String, Runnable> readMix(Map<String, List<Long>> latencies, AtomicInteger errors, AtomicLong bytes) {
        Map<String, String[]> ops = new LinkedHashMap<>();
        ops.put("GET /api/campaigns", new String[]{"/api/campaigns?page=1", influencerToken});
        ops.put("GET /api/creators", new String[]{"/api/creators", brandToken});
        ops.put("GET /api/campaigns/{id}", new String[]{null, influencerToken});
        ops.put("GET /api/creators/{id}", new String[]{null, brandToken});
        ops.put("GET /api/brand/requests", new String[]{"/api/brand/requests", brandToken});
        ops.put("GET /api/brand/campaigns", new String[]{"/api/brand/campaigns?page=1", brandToken});
        ops.put("GET /api/influencer/requests", new String[]{"/api/influencer/requests?page=1", influencerToken});

        Map<String, Runnable> mix = new LinkedHashMap<>();
        ops.forEach((name, spec) -> {
            // live mode without tokens: only the anonymous-readable endpoints can be exercised
            if (spec[1] == null && (name.contains("/brand/") || name.contains("/influencer/"))) return;
            mix.put(name, () -> {
                String path = spec[0];
                if (name.equals("GET /api/campaigns/{id}"))
                    path = "/api/campaigns/" + campaignIds.get(ThreadLocalRandom.current().nextInt(campaignIds.size()));
                if (name.equals("GET /api/creators/{id}"))
                    path = "/api/creators/" + creatorProfileIds.get(ThreadLocalRandom.current().nextInt(creatorProfileIds.size()));
                long t0 = System.nanoTime();
                Resp r = get(path, spec[1]);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                latencies.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(ms);
                bytes.addAndGet(r.body() == null ? 0 : r.body().length());
                if (r.status() != 200) errors.incrementAndGet();
            });
        });
        return mix;
    }

    // ── PERF-1 ───────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("PERF-1: read endpoints use a bounded number of SQL statements (no N+1 queries)")
    void readEndpointsHaveNoNPlusOneQueries() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LIVE_URL == null, "query counting needs the in-process app");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        Map<String, String[]> endpoints = new LinkedHashMap<>();
        endpoints.put("GET /api/campaigns", new String[]{"/api/campaigns?page=1", influencerToken});
        endpoints.put("GET /api/creators", new String[]{"/api/creators", brandToken});
        endpoints.put("GET /api/creators/{id}", new String[]{"/api/creators/" + creatorProfileIds.get(0), brandToken});
        endpoints.put("GET /api/campaigns/{id}", new String[]{"/api/campaigns/" + campaignIds.get(0), influencerToken});
        endpoints.put("GET /api/brand/requests", new String[]{"/api/brand/requests", brandToken});
        endpoints.put("GET /api/brand/campaigns", new String[]{"/api/brand/campaigns?page=1", brandToken});
        endpoints.put("GET /api/influencer/requests", new String[]{"/api/influencer/requests?page=1", influencerToken});

        List<String> violations = new ArrayList<>();
        System.out.println("\n=== PERF-1: SQL statements per request (limit " + MAX_SQL_PER_REQUEST + ") ===");
        endpoints.forEach((name, e) -> {
            get(e[0], e[1]); // warm caches / lazy init
            stats.clear();
            Resp r = get(e[0], e[1]);
            long sql = stats.getPrepareStatementCount();
            System.out.printf("  %-30s status=%d  sql=%5d  payload=%,9d bytes%n", name, r.status(), sql, r.body().length());
            if (sql > MAX_SQL_PER_REQUEST) violations.add(name + " issued " + sql + " SQL statements");
        });
        assertThat(violations).as("endpoints with N+1 query behaviour").isEmpty();
    }

    // ── PERF-2 ───────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("PERF-2: p95 latency of read operations < 200 ms with 100 concurrent users")
    void readLatencyP95Under200msWith100ConcurrentUsers() throws Exception {
        Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        Map<String, Runnable> mix = readMix(latencies, errors, bytes);
        List<Runnable> ops = new ArrayList<>(mix.values());

        // Warm-up (JIT, connection pool, Hibernate caches) — not measured.
        for (int i = 0; i < 3; i++) ops.forEach(Runnable::run);
        latencies.clear(); errors.set(0); bytes.set(0);

        long start = System.nanoTime();
        runConcurrently(VIRTUAL_USERS, () -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < REQUESTS_PER_USER; i++) {
                ops.get(rnd.nextInt(ops.size())).run();
                Thread.sleep(50 + rnd.nextInt(101)); // think time 50-150 ms
            }
            return null;
        });
        double seconds = (System.nanoTime() - start) / 1e9;

        List<Long> all = new ArrayList<>();
        latencies.values().forEach(all::addAll);
        System.out.printf("%n=== PERF-2: %d VUs x %d req, %s ===%n", VIRTUAL_USERS, REQUESTS_PER_USER,
                LIVE_URL != null ? "LIVE " + LIVE_URL : "in-process (H2)");
        System.out.printf("  %-30s %7s %7s %7s %7s %7s%n", "endpoint", "n", "p50", "p95", "p99", "max");
        latencies.forEach((k, v) -> printRow(k, v));
        printRow("ALL READS", all);
        System.out.printf("  throughput=%.1f req/s  errors=%d  avg payload=%,d bytes%n",
                all.size() / seconds, errors.get(), all.isEmpty() ? 0 : bytes.get() / all.size());

        assertThat(errors.get()).as("failed requests under load").isZero();
        assertThat(percentile(all, 95)).as("overall p95 latency (ms) of read operations").isLessThan(P95_TARGET_MS);
    }

    // ── PERF-3 ───────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("PERF-3: creating a campaign does not block on notifying every influencer (p95 < 200 ms)")
    void campaignCreationLatencyIndependentOfAudienceSize() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(LIVE_URL == null, "write test only runs in-process");
        List<Long> ms = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            Resp r = post("/api/campaigns", Map.of("title", "Write-path campaign " + i, "industry", "Tech"), brandToken);
            ms.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(r.status()).isEqualTo(200);
            ids.add(r.json().get("id").asLong());
        }
        System.out.printf("%n=== PERF-3: POST /api/campaigns with %d influencers to notify ===%n", INFLUENCERS);
        printRow("POST /api/campaigns", ms);

        // Notifications must still reach every influencer (eventually) — Observer pattern.
        String link = "/influencer/campaigns/" + ids.get(ids.size() - 1);
        long deadline = System.currentTimeMillis() + 15_000;
        long delivered = 0;
        while (System.currentTimeMillis() < deadline) {
            delivered = notificationRepository.findAll().stream().filter(n -> link.equals(n.getLink())).count();
            if (delivered >= INFLUENCERS) break;
            Thread.sleep(200);
        }
        System.out.printf("  notifications delivered for last campaign: %d / >=%d%n", delivered, INFLUENCERS);

        assertThat(delivered).as("influencers notified about the new campaign").isGreaterThanOrEqualTo(INFLUENCERS);
        assertThat(percentile(ms, 95)).as("p95 campaign-creation latency (ms)").isLessThan(P95_TARGET_MS);
    }

    // ── stats helpers ────────────────────────────────────────────────────────
    private static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static void printRow(String name, List<Long> v) {
        System.out.printf("  %-30s %7d %5dms %5dms %5dms %5dms%n", name, v.size(),
                percentile(v, 50), percentile(v, 95), percentile(v, 99), v.isEmpty() ? 0 : Collections.max(v));
    }
}
