package com.influencehub.backend.controller;

import com.influencehub.backend.dto.CreatorDTO;
import com.influencehub.backend.dto.CreatorListResponse;
import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import com.influencehub.backend.repository.UserRepository;
import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.model.User;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/creators")
public class InfluencerController {

    static final int DEFAULT_PAGE_SIZE = 12;
    static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private InfluencerProfileRepository influencerRepository;

    @Autowired
    private CollaborationRequestRepository requestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getCurrentUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (jwtUtil.validateToken(token)) {
            return userRepository.findByEmail(jwtUtil.extractUsername(token)).orElse(null);
        }
        return null;
    }

    @GetMapping
    public ResponseEntity<CreatorListResponse> getCreators(
            @RequestParam(required = false) List<String> niches,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User currentUser = getCurrentUser(authHeader);

        // Filtering + paging pushed down to the database (was: findAll() then filter in Java)
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, pageSize, Sort.by("id"));
        Page<InfluencerProfile> profiles = (niches != null && !niches.isEmpty())
                ? influencerRepository.findPageByNicheIn(niches, pageable)
                : influencerRepository.findPage(pageable);

        // Build a map of creatorUserId → effective collaboration status
        // We check ALL requests between this brand and each creator (from either side)
        // to determine the correct button: Message (accepted), Requested (pending), or Request/Request Again
        Map<Long, String> requestStatusByCreatorUserId = new HashMap<>();
        if (currentUser != null && "brand".equalsIgnoreCase(currentUser.getRole())) {
            // One projection query of (creatorId, status) instead of loading full request entities
            for (Object[] row : requestRepository.findCreatorStatusPairsForBrand(currentUser)) {
                Long cid = (Long) row[0];
                String existing = requestStatusByCreatorUserId.get(cid);
                String newStatus = row[1] != null ? row[1].toString().toLowerCase() : "pending";
                // Priority: accepted > pending > rejected
                if (existing == null || "accepted".equals(newStatus) ||
                        ("pending".equals(newStatus) && !"accepted".equals(existing))) {
                    requestStatusByCreatorUserId.put(cid, newStatus);
                }
            }
        }

        List<CreatorDTO> dtos = profiles.getContent().stream()
                .map(p -> {
                    CreatorDTO dto = convertToSummaryDTO(p);
                    if (p.getUser() != null) {
                        String rs = requestStatusByCreatorUserId.get(p.getUser().getId());
                        if (rs != null) dto.setRequestStatus(rs);
                    }
                    return dto;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(new CreatorListResponse(dtos, profiles.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CreatorDTO> getCreator(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User currentUser = getCurrentUser(authHeader);

        return influencerRepository.findById(id)
                .map(p -> {
                    CreatorDTO dto = convertToDTO(p);
                    if (currentUser != null && "brand".equalsIgnoreCase(currentUser.getRole()) && p.getUser() != null) {
                        // Targeted query (was: load every request this brand ever sent)
                        requestRepository.findBrandInitiatedStatuses(currentUser, p.getUser().getId()).stream()
                                .findFirst()
                                .ifPresent(s -> dto.setRequestStatus(s.toLowerCase()));
                    }
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/similar")
    public ResponseEntity<List<CreatorDTO>> getSimilar(@PathVariable Long id) {
        // Similar creators = others in the same niche (LIMIT 3 done by the database)
        return influencerRepository.findById(id)
                .map(p -> {
                    List<CreatorDTO> similar = p.getNiche() == null ? List.<CreatorDTO>of()
                            : influencerRepository.findSimilar(p.getNiche(), id, PageRequest.of(0, 3)).stream()
                                    .map(this::convertToSummaryDTO)
                                    .collect(Collectors.toList());
                    return ResponseEntity.ok(similar);
                })
                .orElse(ResponseEntity.ok(Collections.emptyList()));
    }

    /**
     * Lightweight card DTO for listings: omits the base64 avatar / cover / portfolio images
     * (tens of KB each) that the discovery grid never renders. The full profile endpoint
     * (/api/creators/{id}) still returns them.
     */
    private CreatorDTO convertToSummaryDTO(InfluencerProfile profile) {
        return CreatorDTO.builder()
                .id(profile.getId())
                .userId(profile.getUser() != null ? profile.getUser().getId() : null)
                .name(profile.getUser() != null ? profile.getUser().getName() : "Unknown")
                .handle(profile.getHandle())
                .niche(profile.getNiche())
                .followers(profile.getFollowerCount())
                .location(profile.getLocation())
                .bio(profile.getBio())
                .website(profile.getPortfolioUrl())
                .stats(stats(profile))
                .build();
    }

    private static Map<String, String> stats(InfluencerProfile profile) {
        Map<String, String> stats = new HashMap<>();
        stats.put("Followers",      profile.getFollowerCount() != null ? profile.getFollowerCount() : "--");
        stats.put("EngagementRate", profile.getEngagementRate() != null ? profile.getEngagementRate() : "--");
        stats.put("PostsMonth",     profile.getPostsPerMonth() != null ? profile.getPostsPerMonth() : "--");
        stats.put("AvgReach",       profile.getAvgReach() != null ? profile.getAvgReach() : "--");
        return stats;
    }

    private CreatorDTO convertToDTO(InfluencerProfile profile) {
        // Parse portfolio images from JSON array stored in the profile
        java.util.List<String> portfolio = new java.util.ArrayList<>();
        if (profile.getPortfolioImages() != null && !profile.getPortfolioImages().isBlank()) {
            boolean inStr = false;
            StringBuilder cur = new StringBuilder();
            for (char c : profile.getPortfolioImages().toCharArray()) {
                if (c == '"') {
                    if (inStr) { portfolio.add(cur.toString()); cur.setLength(0); inStr = false; }
                    else inStr = true;
                } else if (inStr) {
                    cur.append(c);
                }
            }
        }

        String avatar = profile.getUser() != null ? profile.getUser().getAvatar() : null;

        return CreatorDTO.builder()
                .id(profile.getId())
                .userId(profile.getUser() != null ? profile.getUser().getId() : null)
                .name(profile.getUser() != null ? profile.getUser().getName() : "Unknown")
                .handle(profile.getHandle())
                .niche(profile.getNiche())
                .followers(profile.getFollowerCount())
                .avatar(avatar)
                .coverPhoto(profile.getCoverPhoto())
                .location(profile.getLocation())
                .bio(profile.getBio())
                .website(profile.getPortfolioUrl())
                .stats(stats(profile))
                .portfolio(portfolio)
                .build();
    }
}
