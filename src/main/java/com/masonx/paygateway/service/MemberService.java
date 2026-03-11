package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.merchant.*;
import com.masonx.paygateway.domain.user.User;
import com.masonx.paygateway.domain.user.UserRepository;
import com.masonx.paygateway.security.MerchantUserDetails;
import com.masonx.paygateway.web.dto.InviteMemberRequest;
import com.masonx.paygateway.web.dto.MemberResponse;
import com.masonx.paygateway.web.dto.UpdateMemberRoleRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MemberService {

    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final UserRepository userRepository;
    private final InviteService inviteService;

    public MemberService(MerchantRepository merchantRepository,
                         MerchantUserRepository merchantUserRepository,
                         UserRepository userRepository,
                         InviteService inviteService) {
        this.merchantRepository = merchantRepository;
        this.merchantUserRepository = merchantUserRepository;
        this.userRepository = userRepository;
        this.inviteService = inviteService;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID merchantId) {
        return merchantUserRepository.findAllByMerchantId(merchantId)
                .stream()
                .map(MemberResponse::from)
                .toList();
    }

    public MemberResponse invite(UUID merchantId, InviteMemberRequest req) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));

        User inviter = currentUser();

        // Check if already a member
        if (merchantUserRepository.existsByUser_IdAndMerchant_IdAndStatusNot(
                findOrNullUserId(req.email()), merchantId, MerchantUserStatus.REVOKED)) {
            throw new IllegalStateException("User is already a member of this merchant");
        }

        // Create PENDING_INVITE membership (user may not exist yet)
        User invitee = userRepository.findByEmail(req.email()).orElse(null);
        MerchantUser mu = new MerchantUser();
        mu.setMerchant(merchant);
        mu.setRole(req.role());
        mu.setInvitedBy(inviter);
        mu.setStatus(MerchantUserStatus.PENDING_INVITE);

        if (invitee != null) {
            mu.setUser(invitee);
        } else {
            // Placeholder — user will be created on invite acceptance
            // We store a temporary user record with just the email
            User placeholder = new User();
            placeholder.setEmail(req.email());
            placeholder.setPasswordHash("INVITE_PENDING");
            placeholder = userRepository.save(placeholder);
            mu.setUser(placeholder);
        }

        mu = merchantUserRepository.save(mu);
        inviteService.sendInvite(mu, merchant, inviter);

        return MemberResponse.from(mu);
    }

    public MemberResponse updateRole(UUID merchantId, UUID memberId, UpdateMemberRoleRequest req) {
        MerchantUser mu = merchantUserRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));

        if (!mu.getMerchant().getId().equals(merchantId)) {
            throw new IllegalArgumentException("Member does not belong to this merchant");
        }

        mu.setRole(req.role());
        return MemberResponse.from(merchantUserRepository.save(mu));
    }

    public void revoke(UUID merchantId, UUID memberId) {
        MerchantUser mu = merchantUserRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));

        if (!mu.getMerchant().getId().equals(merchantId)) {
            throw new IllegalArgumentException("Member does not belong to this merchant");
        }

        mu.setStatus(MerchantUserStatus.REVOKED);
        merchantUserRepository.save(mu);
    }

    private User currentUser() {
        MerchantUserDetails principal = (MerchantUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    private UUID findOrNullUserId(String email) {
        return userRepository.findByEmail(email).map(User::getId).orElse(UUID.randomUUID());
    }
}
