package com.influencehub.backend.brand.controller;

import com.influencehub.backend.brand.dto.BrandProfileRequest;
import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.brand.service.BrandProfileService;
import jakarta.validation.Valid;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/brand")
public class BrandProfileController {

    @Autowired
    private BrandProfileService brandService;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/profile/{userId}")
    public ResponseEntity<?> createProfile(
            @PathVariable Long userId,
            @Valid @RequestBody BrandProfileRequest request,
            Authentication authentication
    ) {
        // Ownership check: the authenticated brand may only write its OWN profile.
        User caller = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (caller == null || !caller.getId().equals(userId)) {
            return ResponseEntity.status(403).body("Forbidden: you can only edit your own brand profile");
        }
        BrandProfile saved = brandService.createProfile(userId, request);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "brandName", String.valueOf(saved.getBrandName())));
    }
}