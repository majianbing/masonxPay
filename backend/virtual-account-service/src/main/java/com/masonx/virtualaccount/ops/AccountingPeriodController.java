package com.masonx.virtualaccount.ops;

import com.masonx.virtualaccount.domain.constant.AccountingPeriodStatus;
import com.masonx.virtualaccount.domain.ledger.AccountingPeriodService;
import com.masonx.virtualaccount.ops.dto.AccountingPeriodResponse;
import com.masonx.virtualaccount.ops.dto.CreateAccountingPeriodRequest;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform-finance controls for ledger accounting periods. Internal only:
 * guarded by X-Internal-Token via the /internal/** filter.
 */
@RestController
@RequestMapping("/internal/va/accounting-periods")
public class AccountingPeriodController {

    private final AccountingPeriodService service;

    public AccountingPeriodController(AccountingPeriodService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResult<AccountingPeriodResponse> list(
            @RequestParam(required = false) AccountingPeriodStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int cap = Math.min(size, 100);
        List<AccountingPeriodResponse> content = service.findPage(status, page, cap)
                .stream().map(AccountingPeriodResponse::from).toList();
        long total = service.count(status);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / cap);
        return new PagedResult<>(content, page, cap, total, totalPages);
    }

    @PostMapping
    public AccountingPeriodResponse create(@Valid @RequestBody CreateAccountingPeriodRequest req) {
        return AccountingPeriodResponse.from(service.createOpenPlatformPeriod(
                req.mode(), req.asset(), req.periodStart(), req.periodEnd()));
    }

    @PostMapping("/{accountingPeriodId}/close")
    public AccountingPeriodResponse close(@PathVariable String accountingPeriodId) {
        return AccountingPeriodResponse.from(service.closePeriod(accountingPeriodId));
    }
}
