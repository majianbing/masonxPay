package com.masonx.paygateway.web;

import com.masonx.paygateway.service.MemberService;
import com.masonx.paygateway.web.dto.InviteMemberRequest;
import com.masonx.paygateway.web.dto.MemberResponse;
import com.masonx.paygateway.web.dto.UpdateMemberRoleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'MEMBER', 'READ')")
    public ResponseEntity<List<MemberResponse>> listMembers(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(memberService.listMembers(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'MEMBER', 'CREATE')")
    public ResponseEntity<MemberResponse> invite(@PathVariable UUID merchantId,
                                                  @Valid @RequestBody InviteMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.invite(merchantId, req));
    }

    @PatchMapping("/{memberId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'MEMBER', 'UPDATE')")
    public ResponseEntity<MemberResponse> updateRole(@PathVariable UUID merchantId,
                                                      @PathVariable UUID memberId,
                                                      @Valid @RequestBody UpdateMemberRoleRequest req) {
        return ResponseEntity.ok(memberService.updateRole(merchantId, memberId, req));
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'MEMBER', 'DELETE')")
    public ResponseEntity<Void> revoke(@PathVariable UUID merchantId,
                                        @PathVariable UUID memberId) {
        memberService.revoke(merchantId, memberId);
        return ResponseEntity.noContent().build();
    }
}
