package com.influencehub.backend.nfr;

import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * NFR-CON-7 — fault injection: registration writes a User row AND a profile row.
 * If the second write fails, the first must be rolled back (atomicity), otherwise the
 * system is left with an "orphan" account that can log in but has no profile.
 */
@DisplayName("NFR-CON: Registration atomicity (fault injection)")
class RegistrationAtomicityNfrTest extends NfrTestSupport {

    @MockitoSpyBean
    private InfluencerProfileRepository influencerProfileRepository;

    @Test
    @DisplayName("CON-7: if saving the profile fails, no orphan user account is left behind")
    void failedProfileWriteRollsBackUser() {
        doThrow(new RuntimeException("simulated DB failure while saving profile"))
                .when(influencerProfileRepository).save(any(InfluencerProfile.class));

        String email = uniqueEmail("atomic");
        Resp r = post("/api/auth/register/influencer", Map.of(
                "name", "Atomic", "email", email, "password", "Passw0rd!", "niche", "Tech"), null);

        assertThat(r.status()).as("registration must report failure").isNotEqualTo(200);
        assertThat(userRepository.findByEmail(email))
                .as("orphan user row left after failed registration").isEmpty();
    }
}
