package com.masonx.virtualaccount.provisioning;

import com.masonx.virtualaccount.provisioning.dto.ProvisionMerchantRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/va/merchant-provisioning")
public class MerchantProvisioningController {

    private final MerchantLedgerProvisioningService service;

    public MerchantProvisioningController(MerchantLedgerProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> provision(@Valid @RequestBody ProvisionMerchantRequest req) {
        service.provision(new MerchantProvisioningCommand(
                "manual_merchant_provision_" + req.merchantId(),
                req.organizationId(),
                req.merchantId(),
                req.merchantName(),
                req.modes(),
                req.asset()));
        return ResponseEntity.ok(Map.of("merchantId", req.merchantId(), "provisioned", true));
    }
}
