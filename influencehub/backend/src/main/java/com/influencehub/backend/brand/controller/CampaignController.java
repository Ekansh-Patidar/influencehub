package com.influencehub.backend.brand.controller;

import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.brand.model.Campaign;
import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.event.CampaignCreatedEvent;
import com.influencehub.backend.model.User;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.brand.repository.CampaignRepository;
import com.influencehub.backend.repository.UserRepository;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class CampaignController {

    /** Matches the page size the React Pagination component assumes (Math.ceil(total / 10)). */
    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> CAMPAIGN_STATUSES = Set.of("active", "paused", "closed", "draft");

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CollaborationRequestRepository requestRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private BrandProfileRepository brandProfileRepository;

    /** Resolves a brand user's display name: BrandProfile.brandName first, fallback to user.getName() */
    private String resolveBrandName(User brand) {
        if (brand == null) return "--";
        return brandProfileRepository.findByUserId(brand.getId())
                .map(bp -> bp.getBrandName() != null && !bp.getBrandName().isBlank() ? bp.getBrandName() : brand.getName())
                .orElse(brand.getName());
    }

    /** Batch version of resolveBrandName for a page of campaigns (one query instead of one per row). */
    private Map<Long, String> resolveBrandNames(List<Campaign> campaigns) {
        Set<Long> brandIds = campaigns.stream().filter(c -> c.getBrand() != null)
                .map(c -> c.getBrand().getId()).collect(Collectors.toSet());
        Map<Long, BrandProfile> profiles = brandIds.isEmpty() ? Map.of()
                : brandProfileRepository.findAllByUserIdIn(brandIds).stream()
                        .collect(Collectors.toMap(bp -> bp.getUser().getId(), Function.identity(), (a, b) -> a));
        Map<Long, String> names = new HashMap<>();
        campaigns.stream().map(Campaign::getBrand).filter(b -> b != null).forEach(b -> {
            BrandProfile bp = profiles.get(b.getId());
            names.put(b.getId(), bp != null && bp.getBrandName() != null && !bp.getBrandName().isBlank()
                    ? bp.getBrandName() : b.getName());
        });
        return names;
    }

    /** Request counts for a page of campaigns in one GROUP BY query. */
    private Map<Long, Long> requestCounts(List<Campaign> campaigns) {
        if (campaigns.isEmpty()) return Map.of();
        List<Long> ids = campaigns.stream().map(Campaign::getId).collect(Collectors.toList());
        return requestRepository.countByCampaignIds(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static Pageable pageOf(int page, Integer size) {
        int s = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return PageRequest.of(Math.max(page, 1) - 1, s, Sort.by(Sort.Direction.DESC, "id"));
    }

    private User getCurrentUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (jwtUtil.validateToken(token)) {
            String email = jwtUtil.extractUsername(token);
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }

    /** Compact representation returned by write endpoints (never the raw entity with its User graph). */
    private static Map<String, Object> summary(Campaign c) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("status", c.getStatus());
        map.put("industry", c.getIndustry());
        map.put("postedDate", c.getPostedDate());
        return map;
    }

    @GetMapping("/campaigns")
    public ResponseEntity<?> getAllCampaigns(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String budget,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Long brandId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size) {

        // Filter by brandId if provided (for influencer "View Brand's Campaigns" feature)
        Page<Campaign> result = brandId != null
                ? campaignRepository.findAllByBrandId(brandId, pageOf(page, size))
                : campaignRepository.findAll(pageOf(page, size));
        List<Campaign> campaigns = result.getContent();

        Map<Long, String> brandNames = resolveBrandNames(campaigns);
        Map<Long, Long> counts = requestCounts(campaigns);

        List<Map<String, Object>> responseList = campaigns.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("title", c.getTitle());
            map.put("description", c.getDescription());
            if (c.getBrand() != null) {
                // Use brand company name, not the owner's personal name
                map.put("brandName", brandNames.get(c.getBrand().getId()));
                map.put("brandId", c.getBrand().getId());
            }
            map.put("verified", true);
            map.put("contentTypes", c.getContentTypes());
            map.put("platforms", c.getPlatforms());
            map.put("budget", c.getBudgetMax() != null ? String.format("%.0f", c.getBudgetMax()) : "--");
            map.put("deadline", c.getDraftDeadline() != null ? c.getDraftDeadline().toString() : "--");
            map.put("requestCount", counts.getOrDefault(c.getId(), 0L));
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("campaigns", responseList);
        response.put("total", result.getTotalElements());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/campaigns")
    public ResponseEntity<?> createCampaign(@RequestBody Campaign campaign,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        campaign.setId(null);
        campaign.setBrand(user);
        Campaign saved = campaignRepository.save(campaign);

        // Observer pattern: influencers are notified asynchronously by CampaignNotificationObserver
        eventPublisher.publishEvent(new CampaignCreatedEvent(saved.getId(), saved.getTitle()));

        return ResponseEntity.ok(summary(saved));
    }

    @GetMapping("/brand/campaigns")
    public ResponseEntity<?> getBrandCampaigns(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        Page<Campaign> result = (status == null || status.isEmpty())
                ? campaignRepository.findAllByBrand(user, pageOf(page, size))
                : campaignRepository.findAllByBrandAndStatus(user, status, pageOf(page, size));
        List<Campaign> campaigns = result.getContent();
        Map<Long, Long> counts = requestCounts(campaigns);

        // Map to include request counts as expected by frontend
        List<Map<String, Object>> responseList = campaigns.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("title", c.getTitle());
            map.put("status", c.getStatus());
            map.put("budget", c.getBudgetMax() != null ? String.format("%.0f", c.getBudgetMax()) : "--");
            map.put("requestCount", counts.getOrDefault(c.getId(), 0L));
            map.put("acceptedCount", 0); // Placeholder
            map.put("postedDate", c.getPostedDate());
            map.put("category", c.getIndustry());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("campaigns", responseList);
        response.put("total", result.getTotalElements());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<?> getCampaign(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User currentUser = getCurrentUser(authHeader);
        return campaignRepository.findById(id)
                .map(c -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", c.getId());
                    map.put("title", c.getTitle());
                    map.put("description", c.getDescription());
                    map.put("industry", c.getIndustry());
                    map.put("contentTypes", c.getContentTypes());
                    map.put("platforms", c.getPlatforms());
                    map.put("creatorCount", c.getCreatorCount());
                    map.put("postedDate", c.getPostedDate());
                    map.put("deliverables", c.getDeliverables());
                    map.put("location", c.getLocation());
                    map.put("status", c.getStatus());

                    if (c.getBrand() != null) {
                        map.put("brandName", resolveBrandName(c.getBrand()));
                        map.put("brandId", c.getBrand().getId());
                    }
                    map.put("verified", true);
                    map.put("budget", c.getBudgetMax() != null ? String.format("%.0f", c.getBudgetMax()) : "--");
                    map.put("deadline", c.getDraftDeadline() != null ? c.getDraftDeadline().toString() : "--");

                    // Targeted lookup of this influencer's application (was: load ALL their requests)
                    String applicationStatus = null;
                    if (currentUser != null && "influencer".equalsIgnoreCase(currentUser.getRole())) {
                        applicationStatus = requestRepository.findApplicationStatuses(currentUser, id).stream()
                                .findFirst().orElse(null);
                    }
                    map.put("hasApplied", applicationStatus != null);
                    map.put("applicationStatus", applicationStatus != null ? applicationStatus.toLowerCase() : null);

                    return ResponseEntity.ok((Object) map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/campaigns/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        String status = body.get("status");
        if (status == null || !CAMPAIGN_STATUSES.contains(status.toLowerCase())) {
            return ResponseEntity.badRequest().body("status must be one of " + CAMPAIGN_STATUSES);
        }

        return campaignRepository.findById(id).map(c -> {
            // Ownership check: a brand may only change its own campaigns
            if (c.getBrand() == null || !c.getBrand().getId().equals(user.getId())) {
                return ResponseEntity.status(403).body((Object) "Forbidden: not your campaign");
            }
            c.setStatus(status.toLowerCase());
            campaignRepository.save(c);
            return ResponseEntity.ok((Object) summary(c));
        }).orElse(ResponseEntity.notFound().build());
    }
}
