package com.influencehub.backend.controller;

import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.influencer.model.InfluencerProfile;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/influencer")
public class InfluencerProfileController {

    @Autowired
    private InfluencerProfileRepository influencerProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getCurrentUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (jwtUtil.validateToken(token)) {
            String email = jwtUtil.extractUsername(token);
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }

    /**
     * GET /api/influencer/profile
     * Returns the logged-in influencer's profile.
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getMyProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<InfluencerProfile> profileOpt = influencerProfileRepository.findByUser(user);

        if (profileOpt.isEmpty()) {
            // Return a skeleton profile so the frontend can render something
            Map<String, Object> empty = new HashMap<>();
            empty.put("name", user.getName());
            empty.put("handle", "");
            empty.put("bio", "");
            empty.put("location", "");
            empty.put("niche", "");
            empty.put("platforms", List.of());
            empty.put("baseRate", "");
            empty.put("website", "");
            empty.put("completeness", 0);
            return ResponseEntity.ok(empty);
        }

        return ResponseEntity.ok(buildProfileResponse(profileOpt.get(), user));
    }

    /**
     * PUT /api/influencer/profile
     * Creates or updates the logged-in influencer's profile.
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateMyProfile(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        // Update user name if provided
        if (body.get("name") != null) {
            user.setName(body.get("name").toString());
            userRepository.save(user);
        }

        InfluencerProfile profile = influencerProfileRepository.findByUser(user)
                .orElse(new InfluencerProfile());

        profile.setUser(user);

        if (body.get("handle") != null) profile.setHandle(body.get("handle").toString());
        if (body.get("bio") != null) profile.setBio(body.get("bio").toString());
        if (body.get("location") != null) profile.setLocation(body.get("location").toString());
        if (body.get("niche") != null) profile.setNiche(body.get("niche").toString());
        if (body.get("baseRate") != null) profile.setBaseRate(body.get("baseRate").toString());
        if (body.get("website") != null) profile.setPortfolioUrl(body.get("website").toString());
        if (body.get("engagementRate") != null) profile.setEngagementRate(body.get("engagementRate").toString());
        if (body.get("followerCount") != null) profile.setFollowerCount(body.get("followerCount").toString());
        if (body.get("postsPerMonth") != null) profile.setPostsPerMonth(body.get("postsPerMonth").toString());
        if (body.get("avgReach") != null) profile.setAvgReach(body.get("avgReach").toString());

        // platforms: comes as a List or comma-separated string
        Object platformsObj = body.get("platforms");
        if (platformsObj instanceof List) {
            List<String> platforms = ((List<?>) platformsObj).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            if (!platforms.isEmpty()) {
                profile.setPrimaryPlatform(platforms.get(0));
                profile.setOtherPlatforms(platforms.size() > 1
                        ? String.join(",", platforms.subList(1, platforms.size()))
                        : "");
            }
        }

        // avatar and coverPhoto: stored as base64 data URLs
        if (body.get("avatar") != null && !body.get("avatar").toString().isEmpty()) {
            user.setAvatar(body.get("avatar").toString());
            userRepository.save(user);
        }
        if (body.get("coverPhoto") != null && !body.get("coverPhoto").toString().isEmpty()) {
            profile.setCoverPhoto(body.get("coverPhoto").toString());
        }

        influencerProfileRepository.save(profile);

        return ResponseEntity.ok(buildProfileResponse(profile, user));
    }

    private Map<String, Object> buildProfileResponse(InfluencerProfile p, User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", user.getName());
        map.put("handle", p.getHandle());
        map.put("bio", p.getBio());
        map.put("location", p.getLocation());
        map.put("niche", p.getNiche());
        map.put("baseRate", p.getBaseRate());
        map.put("website", p.getPortfolioUrl());
        map.put("engagementRate", p.getEngagementRate());
        map.put("followerCount", p.getFollowerCount());
        map.put("primaryPlatform", p.getPrimaryPlatform());

        // Reconstruct platforms list: primary + others
        List<String> platforms;
        if (p.getPrimaryPlatform() != null && !p.getPrimaryPlatform().isBlank()) {
            platforms = new java.util.ArrayList<>();
            platforms.add(p.getPrimaryPlatform());
            if (p.getOtherPlatforms() != null && !p.getOtherPlatforms().isBlank()) {
                platforms.addAll(Arrays.asList(p.getOtherPlatforms().split(",")));
            }
        } else {
            platforms = List.of();
        }
        map.put("platforms", platforms);

        map.put("avatar", user.getAvatar());
        map.put("coverPhoto", p.getCoverPhoto());

        // Portfolio images: stored as JSON array, return as List
        List<String> portfolio = jsonToList(p.getPortfolioImages());
        map.put("portfolio", portfolio);

        // Stats sub-object for the stats cards
        Map<String, Object> stats = new HashMap<>();
        stats.put("Followers", p.getFollowerCount() != null ? p.getFollowerCount() : "--");
        stats.put("EngagementRate", p.getEngagementRate() != null ? p.getEngagementRate() : "--");
        stats.put("PostsMonth", p.getPostsPerMonth() != null ? p.getPostsPerMonth() : "--");
        stats.put("AvgReach", p.getAvgReach() != null ? p.getAvgReach() : "--");
        map.put("stats", stats);

        // Also expose flat fields for form pre-filling
        map.put("followerCount", p.getFollowerCount());
        map.put("engagementRate", p.getEngagementRate());
        map.put("postsPerMonth", p.getPostsPerMonth());
        map.put("avgReach", p.getAvgReach());

        // Compute completeness %
        int filled = 0;
        String[] fields = { p.getHandle(), p.getBio(), p.getLocation(), p.getNiche(),
                p.getBaseRate(), p.getPortfolioUrl(), p.getFollowerCount() };
        for (String f : fields) if (f != null && !f.isBlank()) filled++;
        if (user.getAvatar() != null && !user.getAvatar().isBlank()) filled++;
        map.put("completeness", Math.round((filled * 100.0) / (fields.length + 1)));

        return map;
    }

    /**
     * POST /api/influencer/portfolio
     * Adds a new image (base64) to the influencer's portfolio.
     */
    @PostMapping("/portfolio")
    public ResponseEntity<?> addPortfolioImage(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        String imageData = body.get("image");
        if (imageData == null || imageData.isBlank())
            return ResponseEntity.badRequest().body("image is required");

        InfluencerProfile profile = influencerProfileRepository.findByUser(user)
                .orElse(new InfluencerProfile());
        profile.setUser(user);

        List<String> images = jsonToList(profile.getPortfolioImages());
        images.add(imageData);

        profile.setPortfolioImages(listToJson(images));
        influencerProfileRepository.save(profile);

        Map<String, Object> response = new HashMap<>();
        response.put("portfolio", images);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/influencer/portfolio/{index}
     * Removes a portfolio image by index.
     */
    @DeleteMapping("/portfolio/{index}")
    public ResponseEntity<?> deletePortfolioImage(
            @PathVariable int index,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        InfluencerProfile profile = influencerProfileRepository.findByUser(user).orElse(null);
        if (profile == null) return ResponseEntity.notFound().build();

        List<String> images = jsonToList(profile.getPortfolioImages());
        if (index < 0 || index >= images.size())
            return ResponseEntity.badRequest().body("Invalid index");

        images.remove(index);
        profile.setPortfolioImages(listToJson(images));
        influencerProfileRepository.save(profile);

        Map<String, Object> response = new HashMap<>();
        response.put("portfolio", images);
        return ResponseEntity.ok(response);
    }

    // ── Helpers: manual JSON array serialization (avoids needing to import Jackson directly) ──

    private List<String> jsonToList(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isBlank() || json.equals("[]")) return list;
        // State-machine parser: handles commas inside base64 data URL strings
        boolean inString = false;
        boolean escape = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                if (inString) {
                    list.add(current.toString());
                    current.setLength(0);
                    inString = false;
                } else {
                    inString = true;
                }
            } else if (inString) {
                current.append(c);
            }
        }
        return list;
    }

    private String listToJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
