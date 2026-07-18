package com.masonx.virtualaccount.account;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.account.dto.LedgerAccountResponse;
import com.masonx.virtualaccount.account.dto.CreateLedgerAccountRequest;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerAccountManagementService {

    private final LedgerAccountRepository    accountRepo;
    private final SnowflakeIdGenerator idGen;

    public LedgerAccountManagementService(LedgerAccountRepository accountRepo, SnowflakeIdGenerator idGen) {
        this.accountRepo = accountRepo;
        this.idGen       = idGen;
    }

    public LedgerAccountResponse createAccount(CreateLedgerAccountRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;

        LedgerAccount account = new LedgerAccount(
                idGen.generate(MasonXIdPrefix.VA_ACCOUNT.prefix()),
                mode,
                LedgerAccountRole.TENANT,
                req.orgId(),
                req.merchantId(),
                null,
                req.ledgerAccountType(),
                req.asset().toUpperCase(),
                deriveAssetClass(req.asset()),
                deriveScale(req.asset()),
                deriveNormalBalance(req.ledgerAccountType()),
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE
        );

        accountRepo.save(account);
        return toResponse(account);
    }

    public LedgerAccountResponse getAccount(String ledgerAccountId, String merchantId) {
        LedgerAccount account = accountRepo.findById(ledgerAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (!account.merchantId().equals(merchantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return toResponse(account);
    }

    public PagedResult<LedgerAccountResponse> listAccounts(String merchantId, int page, int size) {
        long total = accountRepo.countTenantAccountsByMerchant(merchantId);
        List<LedgerAccountResponse> content = accountRepo
                .findTenantAccountsByMerchant(merchantId, page, size)
                .stream().map(this::toResponse).toList();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedResult<>(content, page, size, total, totalPages);
    }

    // ── Derivation helpers ────────────────────────────────────────────────────

    private NormalBalance deriveNormalBalance(LedgerAccountType type) {
        return switch (type) {
            case CREDIT_LINE, FEE_INCOME, CLEARING, SUSPENSE, BAD_DEBT -> NormalBalance.CREDIT;
            default -> NormalBalance.DEBIT;
        };
    }

    private AssetClass deriveAssetClass(String asset) {
        // Extend here for CRYPTO assets if needed.
        return AssetClass.FIAT;
    }

    private int deriveScale(String asset) {
        return switch (asset.toUpperCase()) {
            case "JPY", "KRW", "CLP", "VND" -> 0;  // zero-decimal currencies
            default -> 2;
        };
    }

    private LedgerAccountResponse toResponse(LedgerAccount a) {
        return new LedgerAccountResponse(
                a.ledgerAccountId(),
                a.mode().name(),
                a.ledgerAccountType().name(),
                a.merchantId(),
                a.asset(),
                a.balance(),
                a.status().name()
        );
    }
}
