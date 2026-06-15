package com.masonx.paygateway.dispute;

import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;
import org.springframework.stereotype.Component;

@Component
public class SquareDisputeAdapter implements DisputeProviderAdapter {

    @Override
    public String providerName() { return "SQUARE"; }

    @Override
    public void submitEvidence(String providerDisputeId, DisputeEvidenceRequest evidence,
                               ProviderCredentials creds) {
        // TODO: implement Square Disputes API evidence submission
        // Square requires: POST /v2/disputes/{dispute_id}/evidence-text or evidence-file,
        // then POST /v2/disputes/{dispute_id}/submit-evidence
        throw new UnsupportedOperationException(
                "Square dispute evidence submission is not yet implemented. " +
                "Please submit evidence directly in your Square Seller Dashboard.");
    }
}
