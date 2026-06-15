package com.masonx.paygateway.dispute;

import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;
import com.stripe.exception.StripeException;
import com.stripe.model.Dispute;
import com.stripe.net.RequestOptions;
import com.stripe.param.DisputeUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StripeDisputeAdapter implements DisputeProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(StripeDisputeAdapter.class);

    @Override
    public String providerName() { return "STRIPE"; }

    @Override
    public void submitEvidence(String providerDisputeId, DisputeEvidenceRequest req,
                               ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe)) {
            throw new IllegalArgumentException("StripeDisputeAdapter requires StripeCredentials");
        }
        RequestOptions options = RequestOptions.builder()
                .setApiKey(stripe.secretKey()).build();

        try {
            DisputeUpdateParams.Evidence.Builder evidenceBuilder = DisputeUpdateParams.Evidence.builder();
            if (req.customerName()            != null) evidenceBuilder.setCustomerName(req.customerName());
            if (req.customerEmail()           != null) evidenceBuilder.setCustomerEmailAddress(req.customerEmail());
            if (req.customerPurchaseIp()      != null) evidenceBuilder.setCustomerPurchaseIp(req.customerPurchaseIp());
            if (req.productDescription()      != null) evidenceBuilder.setProductDescription(req.productDescription());
            if (req.customerCommunication()   != null) evidenceBuilder.setCustomerCommunication(req.customerCommunication());
            if (req.refundPolicy()            != null) evidenceBuilder.setRefundPolicy(req.refundPolicy());
            if (req.refundPolicyDisclosure()  != null) evidenceBuilder.setRefundPolicyDisclosure(req.refundPolicyDisclosure());
            if (req.uncategorizedText()       != null) evidenceBuilder.setUncategorizedText(req.uncategorizedText());

            DisputeUpdateParams params = DisputeUpdateParams.builder()
                    .setEvidence(evidenceBuilder.build())
                    .setSubmit(true)
                    .build();

            Dispute dispute = Dispute.retrieve(providerDisputeId, options);
            dispute.update(params, options);
            log.info("Stripe dispute {} evidence submitted", providerDisputeId);
        } catch (StripeException e) {
            log.error("Failed to submit Stripe dispute evidence for {}: {}", providerDisputeId, e.getMessage());
            throw new IllegalStateException("Stripe evidence submission failed: " + e.getMessage(), e);
        }
    }
}
