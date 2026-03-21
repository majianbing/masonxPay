package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.merchant.MerchantRole;
import java.util.UUID;

public record InviteInfoResponse(
        String inviterEmail,
        String merchantName,
        String inviteeEmail,
        MerchantRole role,
        UUID merchantId
) {}
