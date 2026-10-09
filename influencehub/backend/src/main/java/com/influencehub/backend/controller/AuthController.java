package com.influencehub.backend.controller;

import com.influencehub.backend.dto.*;
import com.influencehub.backend.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request.getEmail(), request.getPassword()));
    }

    @PostMapping("/register/brand")
    public ResponseEntity<?> registerBrand(@RequestBody BrandRegisterRequest request) {
        return ResponseEntity.ok(authService.registerBrand(request));
    }

    @PostMapping("/register/influencer")
    public ResponseEntity<?> registerInfluencer(@RequestBody InfluencerRegisterRequest request) {
        return ResponseEntity.ok(authService.registerInfluencer(request));
    }

    // NOTE: the former unauthenticated GET /api/auth/users endpoint was removed — it returned
    // every account including BCrypt password hashes and was not used by the frontend.
}
