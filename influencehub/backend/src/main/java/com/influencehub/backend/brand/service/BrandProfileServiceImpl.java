package com.influencehub.backend.brand.service;

import com.influencehub.backend.brand.dto.BrandProfileRequest;
import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BrandProfileServiceImpl implements BrandProfileService {

    @Autowired
    private BrandProfileRepository brandRepo;

    @Autowired
    private UserRepository userRepo;

    @Override
    public BrandProfile createProfile(Long userId, BrandProfileRequest request) {

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        BrandProfile profile = brandRepo.findByUserId(userId).orElseGet(BrandProfile::new);
        profile.setBrandName(request.brandName);
        profile.setWebsite(request.website);
        profile.setIndustry(request.industry);
        profile.setDescription(request.description);
        profile.setInstagramHandle(request.instagramHandle);
        profile.setLocation(request.location);
        profile.setLogoUrl(request.logoUrl);
        profile.setBudgetRange(request.budgetRange);
        profile.setLinkedin(request.linkedin);
        profile.setYoutube(request.youtube);
        profile.setUser(user);

        return brandRepo.save(profile);
    }
}