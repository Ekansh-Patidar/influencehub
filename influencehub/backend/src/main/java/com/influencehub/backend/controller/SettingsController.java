package com.influencehub.backend.controller;

import com.influencehub.backend.config.JwtUtil;
import com.influencehub.backend.model.User;
import com.influencehub.backend.repository.UserRepository;
import com.influencehub.backend.brand.repository.BrandProfileRepository;
import com.influencehub.backend.influencer.repository.InfluencerProfileRepository;
import com.influencehub.backend.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BrandProfileRepository brandProfileRepository;

    @Autowired
    private InfluencerProfileRepository influencerProfileRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private AccountService accountService;

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

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        if (body.containsKey("name")) user.setName(body.get("name"));
        if (body.containsKey("email")) {
            String newEmail = body.get("email");
            if (!user.getEmail().equals(newEmail) && userRepository.findByEmail(newEmail).isPresent()) {
                return ResponseEntity.badRequest().body("Email already in use");
            }
            user.setEmail(newEmail);
        }
        
        userRepository.save(user);

        // Also update profiles if they passed location/bio
        if ("brand".equalsIgnoreCase(user.getRole())) {
            brandProfileRepository.findByUserId(user.getId()).ifPresent(bp -> {
                if (body.containsKey("location")) bp.setLocation(body.get("location"));
                if (body.containsKey("bio")) bp.setDescription(body.get("bio"));
                brandProfileRepository.save(bp);
            });
        } else if ("influencer".equalsIgnoreCase(user.getRole())) {
            influencerProfileRepository.findByUser(user).ifPresent(ip -> {
                if (body.containsKey("location")) ip.setLocation(body.get("location"));
                if (body.containsKey("bio")) ip.setBio(body.get("bio"));
                influencerProfileRepository.save(ip);
            });
        }

        Map<String, String> res = new HashMap<>();
        res.put("name", user.getName());
        res.put("email", user.getEmail());
        
        if ("brand".equalsIgnoreCase(user.getRole())) {
            brandProfileRepository.findByUserId(user.getId()).ifPresent(bp -> {
                res.put("companyName", bp.getBrandName());
            });
        }

        return ResponseEntity.ok(res);
    }

    @PutMapping("/password")
    public ResponseEntity<?> updatePassword(@RequestBody Map<String, String> body, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body("Incorrect current password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated"));
    }

    @PutMapping("/notifications")
    public ResponseEntity<?> updateNotifications(@RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");
        // Mock saving notifications preferences
        return ResponseEntity.ok(Map.of("message", "Notifications preferences updated"));
    }

    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = getCurrentUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        // One transaction: user + campaigns, requests, conversations, messages, notifications.
        // Any failure rolls everything back and surfaces as an error (never a false "deleted").
        accountService.deleteAccount(user);

        return ResponseEntity.ok(Map.of("message", "Account deleted"));
    }
}
