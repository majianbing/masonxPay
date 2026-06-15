package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentLink;
import com.masonx.paygateway.domain.payment.PaymentLinkRepository;
import com.masonx.paygateway.domain.payment.PaymentLinkStatus;
import com.masonx.paygateway.web.dto.CreatePaymentLinkRequest;
import com.masonx.paygateway.web.dto.PaymentLinkResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PaymentLinkService {

    @Value("${app.pay-base-url:http://localhost:3000}")
    private String payBaseUrl;

    private final PaymentLinkRepository repo;

    public PaymentLinkService(PaymentLinkRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<PaymentLinkResponse> list(UUID merchantId, ApiKeyMode mode) {
        return repo.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .map(l -> PaymentLinkResponse.from(l, payBaseUrl))
                .toList();
    }

    public PaymentLinkResponse create(UUID merchantId, ApiKeyMode mode, CreatePaymentLinkRequest req) {
        PaymentLink link = new PaymentLink();
        link.setMerchantId(merchantId);
        link.setMode(mode);
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setTitle(req.title());
        link.setDescription(req.description());
        link.setAmount(req.amount());
        link.setCurrency(req.currency().toLowerCase());
        link.setRedirectUrl(req.redirectUrl());
        return PaymentLinkResponse.from(repo.save(link), payBaseUrl);
    }

    public void deactivate(UUID merchantId, UUID linkId) {
        PaymentLink link = repo.findByIdAndMerchantId(linkId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Payment link not found"));
        link.setStatus(PaymentLinkStatus.INACTIVE);
        repo.save(link);
    }
}
