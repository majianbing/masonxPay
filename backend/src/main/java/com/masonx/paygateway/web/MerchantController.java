package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.web.dto.MerchantResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantRepository merchantRepository;

    public MerchantController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @GetMapping("/{merchantId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'MERCHANT_SETTINGS', 'READ')")
    public ResponseEntity<MerchantResponse> get(@PathVariable UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));
        return ResponseEntity.ok(MerchantResponse.from(merchant));
    }
}
