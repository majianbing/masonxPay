package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.merchant.MerchantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteMemberRequest(
        @NotBlank @Email String email,
        @NotNull MerchantRole role
) {}
