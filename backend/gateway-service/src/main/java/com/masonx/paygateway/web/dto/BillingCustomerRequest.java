package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record BillingCustomerRequest(
        @Email
        @Size(max = 320)
        String email,

        @Size(max = 200)
        String name,

        Map<@Size(max = 80) String, @Size(max = 500) String> metadata
) {
}
