package com.influencehub.backend.service;

import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.dto.BrandRegisterRequest;
import com.influencehub.backend.dto.InfluencerRegisterRequest;
import com.influencehub.backend.dto.LoginResponse;
import com.influencehub.backend.exception.ApiException;
import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication & registration business logic (moved out of AuthController so the
 * Controller -> Service -> Repository layering described in the report actually holds).
 */
@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final InfluencerProfileRepository influencerProfileRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** Hash compared against when the email is unknown, so both failure paths cost the same time. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, BrandProfileRepository brandProfileRepository,
                       InfluencerProfileRepository influencerProfileRepository,
                       BCryptPasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.influencerProfileRepository = influencerProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.dummyHash = passwordEncoder.encode("timing-equalisation-dummy");
    }

    /** Same status, body and (approximately) timing for "unknown email" and "wrong password". */
    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        User user = email == null ? null : userRepository.findByEmail(email).orElse(null);
        String hash = user != null ? user.getPassword() : dummyHash;
        boolean matches = password != null && passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        String companyName = null;
        if ("brand".equalsIgnoreCase(user.getRole())) {
            companyName = brandProfileRepository.findByUserId(user.getId())
                    .map(BrandProfile::getBrandName)
                    .orElse(null);
        }
        return LoginResponse.of(user.getName(), user.getEmail(),
                jwtUtil.generateToken(user.getEmail(), user.getRole()), user.getRole(), companyName);
    }

    /** User + BrandProfile are written in ONE transaction: both rows or neither. */
    @Transactional
    public LoginResponse registerBrand(BrandRegisterRequest request) {
        User user = createUser(request.getName(), request.getEmail(), request.getPassword(), "brand");

        BrandProfile profile = new BrandProfile();
        profile.setBrandName(request.getCompanyName());
        profile.setIndustry(request.getIndustry());
        profile.setBudgetRange(request.getBudget());
        profile.setWebsite(request.getWebsite());
        profile.setDescription(request.getDescription());
        profile.setUser(user);
        if (request.getContentTypes() != null) {
            profile.setContentTypes(String.join(",", request.getContentTypes()));
        }
        profile.setInfluencerSize(request.getInfluencerSize());
        if (request.getPlatforms() != null) {
            profile.setPlatforms(String.join(",", request.getPlatforms()));
        }
        brandProfileRepository.save(profile);

        return LoginResponse.of(user.getName(), user.getEmail(),
                jwtUtil.generateToken(user.getEmail(), "brand"), "brand", request.getCompanyName());
    }

    /** User + InfluencerProfile are written in ONE transaction: both rows or neither. */
    @Transactional
    public LoginResponse registerInfluencer(InfluencerRegisterRequest request) {
        User user = createUser(request.getName(), request.getEmail(), request.getPassword(), "influencer");

        InfluencerProfile profile = new InfluencerProfile();
        profile.setHandle(request.getHandle());
        profile.setFollowerCount(request.getFollowerCount());
        profile.setNiche(request.getNiche());
        profile.setLocation(request.getLocation());
        profile.setBio(request.getBio());
        profile.setPrimaryPlatform(request.getPrimaryPlatform());
        profile.setBaseRate(request.getBaseRate());
        profile.setPortfolioUrl(request.getPortfolioUrl());
        profile.setEngagementRate(request.getEngagementRate());
        profile.setUser(user);
        if (request.getOtherPlatforms() != null) {
            profile.setOtherPlatforms(String.join(",", request.getOtherPlatforms()));
        }
        influencerProfileRepository.save(profile);

        return LoginResponse.of(user.getName(), user.getEmail(),
                jwtUtil.generateToken(user.getEmail(), "influencer"), "influencer");
    }

    private User createUser(String name, String email, String rawPassword, String role) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        // Fast path for the common case; the UNIQUE(email) constraint is the real guarantee
        // under concurrency (a racing duplicate fails on insert -> 409 via GlobalExceptionHandler).
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }
}
