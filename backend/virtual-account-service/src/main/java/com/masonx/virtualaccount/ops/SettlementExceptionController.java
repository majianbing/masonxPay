package com.masonx.virtualaccount.ops;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.SettlementExceptionRepository;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;
import com.masonx.virtualaccount.domain.po.SettlementException;
import com.masonx.virtualaccount.ops.dto.DiscardExceptionRequest;
import com.masonx.virtualaccount.ops.dto.SettlementExceptionResponse;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Ops worklist for parked settlement events. Internal only (guarded by
 * X-Internal-Token via the /internal/** filter); platform-admin dashboard UI is
 * a follow-up — this API is the contract it will consume.
 */
@RestController
@RequestMapping("/internal/va/settlement-exceptions")
public class SettlementExceptionController {

    private final SettlementExceptionRepository repo;
    private final SettlementExceptionRetryService retryService;

    public SettlementExceptionController(SettlementExceptionRepository repo,
                                         SettlementExceptionRetryService retryService) {
        this.repo = repo;
        this.retryService = retryService;
    }

    @GetMapping
    public PagedResult<SettlementExceptionResponse> list(
            @RequestParam(required = false) SettlementExceptionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int cap = Math.min(size, 100);
        List<SettlementExceptionResponse> content = repo.findPage(status, page, cap)
                .stream().map(SettlementExceptionResponse::from).toList();
        long total = repo.count(status);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / cap);
        return new PagedResult<>(content, page, cap, total, totalPages);
    }

    @GetMapping("/{exceptionId}")
    public SettlementExceptionResponse get(@PathVariable String exceptionId) {
        SettlementException row = repo.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("VA_NOT_FOUND",
                        "Settlement exception not found: " + exceptionId, 404));
        return SettlementExceptionResponse.from(row);
    }

    @PostMapping("/{exceptionId}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String exceptionId) {
        boolean resolved = retryService.retry(exceptionId);
        return ResponseEntity.ok(Map.of(
                "exceptionId", exceptionId,
                "resolved", resolved,
                "status", resolved ? SettlementExceptionStatus.RESOLVED.name()
                                   : SettlementExceptionStatus.OPEN.name()));
    }

    @PostMapping("/{exceptionId}/discard")
    public ResponseEntity<Void> discard(@PathVariable String exceptionId,
                                        @Valid @RequestBody DiscardExceptionRequest req) {
        SettlementException row = repo.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("VA_NOT_FOUND",
                        "Settlement exception not found: " + exceptionId, 404));
        if (row.status() != SettlementExceptionStatus.OPEN) {
            throw new BusinessException("VA_EXCEPTION_NOT_OPEN",
                    "Only OPEN exceptions can be discarded; status=" + row.status());
        }
        repo.markDiscarded(exceptionId, req.note());
        return ResponseEntity.ok().build();
    }
}
