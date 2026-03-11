package com.masonx.paygateway.web;

import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.service.AuthService;
import com.masonx.paygateway.web.dto.AuthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/memberships")
    public ResponseEntity<List<AuthResponse.OrgMembership>> memberships(
            @AuthenticationPrincipal MerchantUserDetails principal) {
        return ResponseEntity.ok(authService.buildMemberships(principal.getUserId()));
    }
}
