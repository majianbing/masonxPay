package com.masonx.rail.web;

import com.masonx.rail.service.ReconciliationRepository;
import com.masonx.rail.service.ReconciliationRepository.ReconException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Rail reconciliation exception surface.
 *
 * <p>Returns all detected exceptions from the ISO 8583 log (late responses, exhausted
 * reversals) and payments that remain in unresolved states (UNKNOWN, REVERSAL_REQUIRED).
 * Intended for operations dashboards and manual reconciliation workflows.
 */
@RestController
@RequestMapping("/internal/rail/reconciliation")
public class ReconciliationController {

    private final ReconciliationRepository reconRepo;

    public ReconciliationController(ReconciliationRepository reconRepo) {
        this.reconRepo = reconRepo;
    }

    @GetMapping("/exceptions")
    public ResponseEntity<ExceptionsResponse> exceptions(@RequestParam String merchantId) {
        List<ReconException> list = reconRepo.findExceptions(merchantId);
        return ResponseEntity.ok(new ExceptionsResponse(list, list.size()));
    }

    public record ExceptionsResponse(List<ReconException> exceptions, int total) {}
}
