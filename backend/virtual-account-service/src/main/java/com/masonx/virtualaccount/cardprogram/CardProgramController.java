package com.masonx.virtualaccount.cardprogram;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.cardprogram.dto.*;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CardProgramController {

    private final CardProgramManagementService service;
    private final CardSettlementReportService settlementReports;

    public CardProgramController(CardProgramManagementService service,
                                 CardSettlementReportService settlementReports) {
        this.service = service;
        this.settlementReports = settlementReports;
    }

    @PostMapping("/v1/issuer-partners")
    public ResponseEntity<IssuerPartnerResponse> createIssuerPartner(
            @Valid @RequestBody CreateIssuerPartnerRequest req) {
        return ResponseEntity.ok(service.createIssuerPartner(req));
    }

    @GetMapping("/v1/issuer-partners/{issuerPartnerId}")
    public ResponseEntity<IssuerPartnerResponse> getIssuerPartner(
            @PathVariable String issuerPartnerId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode) {
        return ResponseEntity.ok(service.getIssuerPartner(issuerPartnerId, merchantId, mode));
    }

    @GetMapping("/v1/issuer-partners")
    public ResponseEntity<PagedResult<IssuerPartnerResponse>> listIssuerPartners(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listIssuerPartners(merchantId, mode, page, Math.min(size, 100)));
    }

    @PostMapping("/v1/card-programs")
    public ResponseEntity<CardProgramResponse> createCardProgram(
            @Valid @RequestBody CreateCardProgramRequest req) {
        return ResponseEntity.ok(service.createCardProgram(req));
    }

    @GetMapping("/v1/card-programs/{programId}")
    public ResponseEntity<CardProgramResponse> getCardProgram(
            @PathVariable String programId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode) {
        return ResponseEntity.ok(service.getCardProgram(programId, merchantId, mode));
    }

    @GetMapping("/v1/card-programs")
    public ResponseEntity<PagedResult<CardProgramResponse>> listCardPrograms(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listCardPrograms(merchantId, mode, page, Math.min(size, 100)));
    }

    @PostMapping("/v1/cardholders")
    public ResponseEntity<CardholderResponse> createCardholder(
            @Valid @RequestBody CreateCardholderRequest req) {
        return ResponseEntity.ok(service.createCardholder(req));
    }

    @GetMapping("/v1/cardholders/{cardholderId}")
    public ResponseEntity<CardholderResponse> getCardholder(
            @PathVariable String cardholderId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode) {
        return ResponseEntity.ok(service.getCardholder(cardholderId, merchantId, mode));
    }

    @GetMapping("/v1/cardholders")
    public ResponseEntity<PagedResult<CardholderResponse>> listCardholders(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listCardholders(merchantId, mode, page, Math.min(size, 100)));
    }

    @PostMapping("/internal/issuer-settlement-reports")
    public ResponseEntity<CardSettlementReportResponse> ingestSettlementReport(
            @Valid @RequestBody IngestCardSettlementReportRequest req) {
        return ResponseEntity.ok(settlementReports.ingest(req));
    }

    @GetMapping("/v1/card-programs/{programId}/settlement-reports")
    public ResponseEntity<PagedResult<CardSettlementReportResponse>> listSettlementReports(
            @PathVariable String programId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(settlementReports.list(merchantId, mode, programId, page, Math.min(size, 100)));
    }

    @GetMapping("/v1/card-settlement-reports/{reportId}")
    public ResponseEntity<CardSettlementReportResponse> getSettlementReport(
            @PathVariable String reportId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode) {
        return ResponseEntity.ok(settlementReports.get(reportId, merchantId, mode));
    }

    @GetMapping("/v1/card-programs/{programId}/settlement-reconciliation-summary")
    public ResponseEntity<CardSettlementReconciliationSummaryResponse> getSettlementReconciliationSummary(
            @PathVariable String programId,
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam java.time.LocalDate settlementDate,
            @RequestParam String currency) {
        return ResponseEntity.ok(settlementReports.summarize(
                merchantId, mode, programId, settlementDate, currency));
    }
}
