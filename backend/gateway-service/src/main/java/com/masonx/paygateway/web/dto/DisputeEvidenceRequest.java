package com.masonx.paygateway.web.dto;

import java.util.List;
import java.util.UUID;

public record DisputeEvidenceRequest(
        String customerName,
        String customerEmail,
        String customerPurchaseIp,
        String productDescription,
        String customerCommunication,
        String refundPolicy,
        String refundPolicyDisclosure,
        String shippingDocumentationUrl,
        String uncategorizedText,
        List<UUID> fileIds  // IDs of previously uploaded DisputeEvidenceFiles
) {}
