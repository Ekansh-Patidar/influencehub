package com.influencehub.backend.nfr;

import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.brand.repository.CampaignRepository;
import com.influencehub.backend.model.CollaborationRequest;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-CON — "System maintains data consistency across operations" and
 * "System supports multiple users accessing simultaneously" (Report §1.1, concern C4,
 * §4.2.1.4 "strong data consistency ... single ACID-compliant transaction").
 *
 * These tests fire genuinely concurrent HTTP requests at the running app to expose
 * check-then-act races, lost updates and partial (non-atomic) writes.
 */
@DisplayName("NFR-CON: Data consistency under concurrency")
class DataConsistencyNfrTest extends NfrTestSupport {

    private static final int PARALLEL = 20;

    @Autowired private CollaborationRequestRepository requestRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private BrandProfileRepository brandProfileRepository;

    // ── CON-1 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-1: 20 simultaneous sign-ups with the same email create exactly one account")
    void concurrentRegistrationWithSameEmailCreatesOneAccount() throws Exception {
        String email = uniqueEmail("race");
        List<Resp> results = runConcurrently(PARALLEL, () -> post("/api/auth/register/influencer", Map.of(
                "name", "Racer", "email", email, "password", "Passw0rd!", "niche", "Tech"), null));

        long accounts = userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).count();
        long ok = results.stream().filter(r -> r.status() == 200).count();

        assertThat(accounts).as("user rows with email " + email).isEqualTo(1);
        assertThat(ok).as("successful registrations").isEqualTo(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.status()).as("duplicate sign-up status").isEqualTo(409));
    }

    // ── CON-2 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-2: 20 simultaneous identical brand->creator requests persist exactly one PENDING request")
    void concurrentDuplicateBrandRequestsAreDeduplicated() throws Exception {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();

        List<Resp> results = runConcurrently(PARALLEL, () ->
                post("/api/requests", Map.of("creatorId", creator.id(), "description", "collab?"), brand.token()));

        List<Long> rows = requestsBetween(brand.id(), creator.id());
        assertThat(rows).as("PENDING requests between the same brand and creator").hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
    }

    // ── CON-3 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-3: 20 simultaneous applications by one creator to one campaign persist exactly one")
    void concurrentDuplicateApplicationsAreDeduplicated() throws Exception {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();
        long campaignId = createCampaign(brand, "Race campaign");

        List<Resp> results = runConcurrently(PARALLEL, () ->
                post("/api/requests", Map.of("campaignId", campaignId, "message", "apply"), creator.token()));

        List<Long> rows = requestsBetween(brand.id(), creator.id());
        assertThat(rows).as("applications by the same creator to the same campaign").hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
    }

    // ── CON-4 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-4: request status follows the PENDING -> ACCEPTED|REJECTED state machine")
    void requestStatusFollowsStateMachine() {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();
        long reqId = post("/api/requests", Map.of("creatorId", creator.id(), "description", "hi"), brand.token())
                .json().get("id").asLong();

        Resp garbage = put("/api/requests/" + reqId + "/status", Map.of("status", "HACKED"), creator.token());
        Resp reject = put("/api/requests/" + reqId + "/status", Map.of("status", "REJECTED"), creator.token());
        Resp resurrect = put("/api/requests/" + reqId + "/status", Map.of("status", "ACCEPTED"), creator.token());

        assertThat(garbage.status()).as("unknown status value").isEqualTo(400);
        assertThat(reject.status()).as("PENDING -> REJECTED").isEqualTo(200);
        assertThat(resurrect.status()).as("REJECTED -> ACCEPTED (illegal transition)").isEqualTo(409);
        assertThat(requestRepository.findById(reqId).orElseThrow().getStatus()).isEqualTo("REJECTED");
    }

    // ── CON-5 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-5: racing ACCEPT vs REJECT on one request -> exactly one wins, no lost update")
    void concurrentAcceptAndRejectHaveExactlyOneWinner() throws Exception {
        TestUser brand = registerBrand();
        List<String> anomalies = new ArrayList<>();

        for (int round = 0; round < 10; round++) {
            TestUser creator = registerInfluencer();
            long reqId = post("/api/requests", Map.of("creatorId", creator.id(), "description", "race"), brand.token())
                    .json().get("id").asLong();

            String[] decisions = {"ACCEPTED", "REJECTED"};
            int[] idx = {0};
            List<Resp> results = runConcurrently(2, () -> {
                String d;
                synchronized (idx) { d = decisions[idx[0]++]; }
                Resp r = put("/api/requests/" + reqId + "/status", Map.of("status", d), creator.token());
                return new Resp(r.status(), d);
            });

            List<Resp> winners = results.stream().filter(r -> r.status() == 200).collect(Collectors.toList());
            String finalStatus = requestRepository.findById(reqId).orElseThrow().getStatus();
            if (winners.size() != 1) {
                anomalies.add("round " + round + ": " + winners.size() + " callers were told they succeeded "
                        + "(final DB status " + finalStatus + ")");
            } else if (!winners.get(0).body().equals(finalStatus)) {
                anomalies.add("round " + round + ": winner " + winners.get(0).body() + " but DB says " + finalStatus);
            }
        }
        assertThat(anomalies).as("lost-update anomalies").isEmpty();
    }

    // ── CON-6 ────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("CON-6: account deletion is all-or-nothing (report §4.2.1.4 ACID claim)")
    void accountDeletionIsAtomic() {
        TestUser brand = registerBrand();
        TestUser creator = registerInfluencer();
        createCampaign(brand, "Campaign that must be cleaned up");
        long reqId = post("/api/requests", Map.of("creatorId", creator.id(), "description", "x"), brand.token())
                .json().get("id").asLong();
        put("/api/requests/" + reqId + "/status", Map.of("status", "ACCEPTED"), creator.token());
        long convId = post("/api/conversations", Map.of("otherUserId", creator.id()), brand.token())
                .json().get("id").asLong();
        post("/api/conversations/" + convId + "/messages", Map.of("text", "hello"), brand.token());

        Resp del = delete("/api/settings/account", brand.token());

        boolean userGone = userRepository.findById(brand.id()).isEmpty();
        boolean profileGone = brandProfileRepository.findByUserId(brand.id()).isEmpty();
        long campaignsLeft = campaignRepository.findAll().stream()
                .filter(c -> c.getBrand() != null && c.getBrand().getId().equals(brand.id())).count();

        if (del.status() == 200) {
            assertThat(userGone).as("API said 'Account deleted' -> user row must be gone").isTrue();
            assertThat(profileGone).as("brand profile removed").isTrue();
            assertThat(campaignsLeft).as("campaigns owned by deleted brand").isZero();
            assertThat(get("/api/notifications", brand.token()).status())
                    .as("token of a deleted account").isEqualTo(401);
        } else {
            assertThat(userGone || profileGone)
                    .as("deletion failed -> NOTHING may have been deleted (no partial state)").isFalse();
        }
    }

    /** IDs (not entities) so assertion messages never touch lazy JPA state outside a session. */
    private List<Long> requestsBetween(long brandId, long creatorId) {
        User b = userRepository.findById(brandId).orElseThrow();
        return requestRepository.findAllByBrand(b).stream()
                .filter(r -> r.getCreator().getId().equals(creatorId))
                .map(CollaborationRequest::getId)
                .collect(Collectors.toList());
    }
}
