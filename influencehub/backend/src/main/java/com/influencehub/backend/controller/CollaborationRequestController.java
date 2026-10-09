package com.influencehub.backend.controller;

import com.influencehub.backend.brand.model.BrandProfile;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.model.CollaborationRequest;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.CollaborationRequestRepository;
import com.influencehub.backend.repository.UserRepository;
import com.influencehub.backend.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CollaborationRequestController — handles brand-to-creator collaboration
 * requests.
 *
 * Design Patterns used:
 * - Facade Pattern: This controller acts as a facade over the collaboration service,
 * providing a simplified API surface for request lifecycle management.
 * - State machine: Status transitions (PENDING → ACCEPTED / REJECTED) and their
 * authorization rules live in CollaborationService.
 */
@RestController
@RequestMapping("/api")
public class CollaborationRequestController {

    @Autowired
    private CollaborationRequestRepository requestRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InfluencerProfileRepository influencerProfileRepository;

    @Autowired
    private BrandProfileRepository brandProfileRepository;

    /** Returns brand's company name from BrandProfile, falls back to user.getName() */
    private String resolveBrandName(User brand) {
        if (brand == null) return "--";
        return brandProfileRepository.findByUserId(brand.getId())
                .map(bp -> bp.getBrandName() != null && !bp.getBrandName().isBlank() ? bp.getBrandName() : brand.getName())
                .orElse(brand.getName());
    }

    /** Batch version of resolveBrandName: one query for a whole list of brands. */
    private Map<Long, String> resolveBrandNames(List<User> brands) {
        Set<Long> ids = brands.stream().map(User::getId).collect(Collectors.toSet());
        Map<Long, BrandProfile> profiles = ids.isEmpty() ? Map.of()
                : brandProfileRepository.findAllByUserIdIn(ids).stream()
                        .collect(Collectors.toMap(bp -> bp.getUser().getId(), Function.identity(), (a, b) -> a));
        Map<Long, String> names = new HashMap<>();
        for (User b : brands) {
            BrandProfile bp = profiles.get(b.getId());
            names.put(b.getId(), bp != null && bp.getBrandName() != null && !bp.getBrandName().isBlank()
                    ? bp.getBrandName() : b.getName());
        }
        return names;
    }

    private User getCurrentUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return null;
        String token = authHeader.substring(7);
        if (jwtUtil.validateToken(token)) {
            String email = jwtUtil.extractUsername(token);
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }

    /**
     * POST /api/requests
     * Brand sends a collaboration request to a creator.
     * campaignId is OPTIONAL — brand can send a request without linking to a
     * campaign.
     * A description field allows the brand to describe their intent.
     */
    @PostMapping("/requests")
    public ResponseEntity<?> createRequest(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User currentUser = getCurrentUser(authHeader);
        if (currentUser == null)
            return ResponseEntity.status(401).body("Unauthorized");

        if ("influencer".equalsIgnoreCase(currentUser.getRole())) {
            // Influencer applying to a campaign
            Object campaignIdObj = body.get("campaignId");
            if (campaignIdObj == null) {
                return ResponseEntity.badRequest().body("campaignId is required for influencers");
            }
            String message = (String) body.get("message");
            Object rateObj = body.get("proposedRate");
            if (rateObj != null && !rateObj.toString().isEmpty()) {
                message = (message != null ? message + "\n" : "") + "Proposed Rate: ₹" + rateObj.toString();
            }
            CollaborationRequest saved = collaborationService.apply(
                    currentUser, Long.valueOf(campaignIdObj.toString()), message);

            Map<String, Object> response = new HashMap<>();
            response.put("id", saved.getId());
            response.put("status", saved.getStatus());
            return ResponseEntity.ok(response);

        } else {
            // Brand requesting an influencer
            Object creatorIdObj = body.get("creatorId");
            if (creatorIdObj == null)
                return ResponseEntity.badRequest().body("creatorId is required");

            Long campaignId = null;
            if (body.get("campaignId") != null) {
                try {
                    campaignId = Long.valueOf(body.get("campaignId").toString());
                } catch (NumberFormatException ignored) {
                }
            }
            CollaborationRequest saved = collaborationService.invite(currentUser,
                    Long.valueOf(creatorIdObj.toString()), campaignId,
                    (String) body.get("description"), (String) body.get("message"),
                    resolveBrandName(currentUser));

            Map<String, Object> response = new HashMap<>();
            response.put("id", saved.getId());
            response.put("status", saved.getStatus());
            response.put("creatorId", saved.getCreator().getId());
            response.put("timestamp", saved.getTimestamp());

            return ResponseEntity.ok(response);
        }
    }

    /**
     * GET /api/brand/requests
     * Returns INBOUND collaboration requests received by the brand
     * (i.e. requests initiated by influencers applying to the brand's campaigns).
     * Brand-sent outbound requests are tracked via the creator profile endpoint.
     */
    @GetMapping("/brand/requests")
    public ResponseEntity<?> getBrandRequests(
            @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null)
            return ResponseEntity.status(401).body("Unauthorized");

        // Only INFLUENCER-initiated requests (inbound to brand) — one JOIN FETCH query
        List<CollaborationRequest> requests = requestRepository.findForBrand(user, "INFLUENCER");
        if (status != null && !status.isEmpty()) {
            requests = requests.stream()
                    .filter(r -> status.equalsIgnoreCase(r.getStatus()))
                    .collect(Collectors.toList());
        }

        // Creator profile ids for all rows in one query (was one query per row)
        Set<Long> creatorIds = requests.stream().map(r -> r.getCreator().getId()).collect(Collectors.toSet());
        Map<Long, Long> profileIdByUserId = creatorIds.isEmpty() ? Map.of()
                : influencerProfileRepository.findProfileIdsByUserIds(creatorIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1], (a, b) -> a));

        List<Map<String, Object>> responseList = requests.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            if (r.getCampaign() != null) {
                map.put("campaignTitle", r.getCampaign().getTitle());
                map.put("campaignId", r.getCampaign().getId());
                map.put("category", r.getCampaign().getIndustry());
            }
            map.put("creatorName", r.getCreator().getName());
            map.put("creatorId", r.getCreator().getId());
            // InfluencerProfile id so brand can link to creator profile
            Long profileId = profileIdByUserId.get(r.getCreator().getId());
            if (profileId != null) map.put("creatorProfileId", profileId);
            map.put("status", r.getStatus() != null ? r.getStatus().toLowerCase() : "pending");
            map.put("initiatedBy", r.getInitiatedBy());
            map.put("date", r.getTimestamp() != null ? r.getTimestamp().toLocalDate().toString() : "--");

            String msg = r.getDescription() != null ? r.getDescription() : r.getMessage();
            map.put("message", msg);
            if (msg != null && msg.contains("Proposed Rate: ₹")) {
                int idx = msg.indexOf("Proposed Rate: ₹");
                map.put("proposedRate", msg.substring(idx + 16).trim());
            }
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("requests", responseList);
        response.put("total", responseList.size());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/influencer/requests
     * Returns INBOUND brand-initiated requests received by the influencer.
     * Also includes brandId so influencer can browse that brand's campaigns.
     */
    @GetMapping("/influencer/requests")
    public ResponseEntity<?> getInfluencerRequests(
            @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null)
            return ResponseEntity.status(401).body("Unauthorized");

        // Only BRAND-initiated requests (inbound to influencer) — one JOIN FETCH query
        List<CollaborationRequest> requests = requestRepository.findForCreator(user, "BRAND");
        if (status != null && !status.isEmpty()) {
            requests = requests.stream()
                    .filter(r -> status.equalsIgnoreCase(r.getStatus()))
                    .collect(Collectors.toList());
        }

        Map<Long, String> brandNames = resolveBrandNames(
                requests.stream().map(CollaborationRequest::getBrand).distinct().collect(Collectors.toList()));

        List<Map<String, Object>> responseList = requests.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            if (r.getCampaign() != null) {
                map.put("campaignTitle", r.getCampaign().getTitle());
                map.put("category", r.getCampaign().getIndustry());
            }
            map.put("brandName", r.getBrand() != null ? brandNames.get(r.getBrand().getId()) : "--");
            map.put("brandId", r.getBrand() != null ? r.getBrand().getId() : null);
            map.put("status", r.getStatus() != null ? r.getStatus().toLowerCase() : "pending");
            map.put("date", r.getTimestamp() != null ? r.getTimestamp().toLocalDate().toString() : "--");

            String msg = r.getDescription() != null ? r.getDescription() : r.getMessage();
            map.put("message", msg);
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("requests", responseList);
        response.put("total", responseList.size());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/influencer/applications
     * Returns the influencer's own campaign applications (INFLUENCER-initiated).
     * Used by Browse Campaigns page to show per-card status badges.
     */
    @GetMapping("/influencer/applications")
    public ResponseEntity<?> getInfluencerApplications(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        List<CollaborationRequest> apps = requestRepository.findApplicationsByCreator(user);

        List<Map<String, Object>> responseList = apps.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("campaignId", r.getCampaign() != null ? r.getCampaign().getId() : null);
            map.put("campaignTitle", r.getCampaign() != null ? r.getCampaign().getTitle() : null);
            map.put("status", r.getStatus() != null ? r.getStatus().toLowerCase() : "pending");
            map.put("date", r.getTimestamp() != null ? r.getTimestamp().toLocalDate().toString() : "--");
            String msg = r.getDescription() != null ? r.getDescription() : r.getMessage();
            map.put("message", msg);
            if (msg != null && msg.contains("Proposed Rate: ₹")) {
                int idx = msg.indexOf("Proposed Rate: ₹");
                map.put("proposedRate", msg.substring(idx + 16).trim());
            }
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(responseList);
    }


    /**
     * PUT /api/requests/{id}/status
     * Updates the status of a collaboration request. Only the RECIPIENT may accept/reject:
     * - Brand accepts/rejects incoming applications on their Requests page.
     * - Creator accepts/rejects brand outreach on their MyRequests page.
     */
    @PutMapping("/requests/{id}/status")
    public ResponseEntity<?> updateRequestStatus(@PathVariable Long id, @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null)
            return ResponseEntity.status(401).body("Unauthorized");

        CollaborationRequest req = collaborationService.updateStatus(id, body.get("status"), user);

        Map<String, Object> response = new HashMap<>();
        response.put("id", req.getId());
        response.put("status", req.getStatus());
        return ResponseEntity.ok(response);
    }
}
