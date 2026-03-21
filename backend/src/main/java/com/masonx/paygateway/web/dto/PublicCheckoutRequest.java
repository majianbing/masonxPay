package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PublicCheckoutRequest(
        @NotBlank String gatewayToken    // gw_tok_xxx — opaque token from POST /pub/tokenize
) {}
