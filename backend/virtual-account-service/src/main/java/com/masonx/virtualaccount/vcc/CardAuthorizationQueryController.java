package com.masonx.virtualaccount.vcc;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.vcc.dto.CardAuthorizationResponse;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/vcc/authorizations")
public class CardAuthorizationQueryController {

    private final CardAuthorizationQueryService service;

    public CardAuthorizationQueryController(CardAuthorizationQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PagedResult<CardAuthorizationResponse>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.list(merchantId, mode, page, size));
    }
}
