package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Size;

/**
 * For new users accepting an invite — password is required only if not yet registered.
 */
public record AcceptInviteRequest(
        @Size(min = 8) String password
) {}
