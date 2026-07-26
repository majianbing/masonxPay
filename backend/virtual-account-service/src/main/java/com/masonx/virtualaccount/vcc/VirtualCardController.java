package com.masonx.virtualaccount.vcc;

import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.CreateVccResponse;
import com.masonx.virtualaccount.vcc.dto.CardLifecycleRequest;
import com.masonx.virtualaccount.vcc.dto.CardControlResponse;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import com.masonx.virtualaccount.vcc.dto.UpdateCardControlsRequest;
import com.masonx.virtualaccount.vcc.dto.VccResponse;
import com.masonx.virtualaccount.vcc.dto.WithdrawVccRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Merchant-facing Virtual Credit Card management API.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>POST /v1/vcc/cards → create card (balance = 0, returns testPan once)
 *   <li>POST /v1/vcc/cards/{cardId}/fund → transfer from WALLET to card
 *   <li>GET  /v1/vcc/cards/{cardId} → view card + balance
 *   <li>GET  /v1/vcc/cards?merchantId=... → list all cards for a merchant
 *   <li>DELETE /v1/vcc/cards/{cardId}?merchantId=... → close card, sweep balance back
 * </ol>
 */
@RestController
@RequestMapping("/v1/vcc/cards")
public class VirtualCardController {

    private final VirtualCardService vccService;

    public VirtualCardController(VirtualCardService vccService) {
        this.vccService = vccService;
    }

    @PostMapping
    public ResponseEntity<CreateVccResponse> create(@Valid @RequestBody CreateVccRequest req) {
        return ResponseEntity.ok(vccService.createCard(req));
    }

    @PostMapping("/{cardId}/fund")
    public ResponseEntity<VccResponse> fund(@PathVariable String cardId,
                                             @Valid @RequestBody FundVccRequest req) {
        return ResponseEntity.ok(vccService.fundCard(cardId, req));
    }

    @PostMapping("/{cardId}/withdraw")
    public ResponseEntity<VccResponse> withdraw(@PathVariable String cardId,
                                                @Valid @RequestBody WithdrawVccRequest req) {
        return ResponseEntity.ok(vccService.withdrawCard(cardId, req));
    }

    @PutMapping("/{cardId}/controls")
    public ResponseEntity<CardControlResponse> updateControls(@PathVariable String cardId,
                                                              @Valid @RequestBody UpdateCardControlsRequest req) {
        return ResponseEntity.ok(vccService.updateCardControls(cardId, req));
    }

    @GetMapping("/{cardId}/controls")
    public ResponseEntity<CardControlResponse> getControls(@PathVariable String cardId,
                                                           @RequestParam String merchantId) {
        return ResponseEntity.ok(vccService.getCardControls(cardId, merchantId));
    }

    @GetMapping("/{cardId}")
    public ResponseEntity<VccResponse> get(@PathVariable String cardId) {
        return ResponseEntity.ok(vccService.getCard(cardId));
    }

    @GetMapping
    public ResponseEntity<PagedResult<VccResponse>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(vccService.listCards(merchantId, page, Math.min(size, 100)));
    }

    @DeleteMapping("/{cardId}")
    public ResponseEntity<Void> close(@PathVariable String cardId,
                                       @RequestParam String merchantId) {
        vccService.closeCard(cardId, merchantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{cardId}/close")
    public ResponseEntity<Void> closePost(@PathVariable String cardId,
                                          @Valid @RequestBody CardLifecycleRequest req) {
        vccService.closeCard(cardId, req.merchantId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{cardId}/lock")
    public ResponseEntity<VccResponse> lock(@PathVariable String cardId,
                                            @Valid @RequestBody CardLifecycleRequest req) {
        return ResponseEntity.ok(vccService.lockCard(cardId, req.merchantId(), req.reason()));
    }

    @PostMapping("/{cardId}/unlock")
    public ResponseEntity<VccResponse> unlock(@PathVariable String cardId,
                                              @Valid @RequestBody CardLifecycleRequest req) {
        return ResponseEntity.ok(vccService.unlockCard(cardId, req.merchantId()));
    }

    @PostMapping("/{cardId}/terminate")
    public ResponseEntity<VccResponse> terminate(@PathVariable String cardId,
                                                 @Valid @RequestBody CardLifecycleRequest req) {
        return ResponseEntity.ok(vccService.terminateCard(cardId, req.merchantId(), req.reason()));
    }
}
