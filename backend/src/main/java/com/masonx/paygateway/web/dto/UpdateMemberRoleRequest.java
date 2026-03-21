package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.merchant.MerchantRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
        @NotNull MerchantRole role
) {}
