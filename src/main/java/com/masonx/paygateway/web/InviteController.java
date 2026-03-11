package com.masonx.paygateway.web;

import com.masonx.paygateway.service.InviteService;
import com.masonx.paygateway.web.dto.AcceptInviteRequest;
import com.masonx.paygateway.web.dto.InviteInfoResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    /**
     * Get invite info (no auth required — link is public but token-gated).
     */
    @GetMapping("/{token}")
    public ResponseEntity<InviteInfoResponse> getInviteInfo(@PathVariable String token) {
        return ResponseEntity.ok(inviteService.getInviteInfo(token));
    }

    /**
     * Accept invite. Password required only for new users.
     */
    @PostMapping("/{token}/accept")
    public ResponseEntity<Void> acceptInvite(@PathVariable String token,
                                              @Valid @RequestBody(required = false) AcceptInviteRequest req) {
        inviteService.acceptInvite(token, req != null ? req : new AcceptInviteRequest(null));
        return ResponseEntity.noContent().build();
    }
}
