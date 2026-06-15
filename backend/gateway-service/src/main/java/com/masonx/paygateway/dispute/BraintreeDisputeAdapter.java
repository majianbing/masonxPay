package com.masonx.paygateway.dispute;

import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;
import org.springframework.stereotype.Component;

@Component
public class BraintreeDisputeAdapter implements DisputeProviderAdapter {

    @Override
    public String providerName() { return "BRAINTREE"; }

    @Override
    public void submitEvidence(String providerDisputeId, DisputeEvidenceRequest evidence,
                               ProviderCredentials creds) {
        // TODO: implement Braintree Disputes API evidence submission
        // Braintree requires adding text/file evidence and then finalizing via their GraphQL or REST API
        throw new UnsupportedOperationException(
                "Braintree dispute evidence submission is not yet implemented. " +
                "Please submit evidence directly in your Braintree Control Panel.");
    }
}
