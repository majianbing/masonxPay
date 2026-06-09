package com.masonx.paygateway.dispute;

import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;

public interface DisputeProviderAdapter {

    String providerName();

    /**
     * Submits evidence for a dispute to the provider and marks it as under review.
     * Implementations should be idempotent — providers typically accept one submission.
     */
    void submitEvidence(String providerDisputeId, DisputeEvidenceRequest evidence,
                        ProviderCredentials creds);
}
