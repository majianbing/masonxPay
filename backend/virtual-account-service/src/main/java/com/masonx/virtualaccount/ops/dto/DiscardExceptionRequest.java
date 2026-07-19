package com.masonx.virtualaccount.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Discarding a parked settlement event requires an explanation for the audit trail. */
public record DiscardExceptionRequest(
        @NotBlank @Size(max = 500) String note
) {
}
