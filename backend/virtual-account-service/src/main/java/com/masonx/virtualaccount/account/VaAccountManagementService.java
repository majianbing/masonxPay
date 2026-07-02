package com.masonx.virtualaccount.account;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.account.dto.AccountResponse;
import com.masonx.virtualaccount.account.dto.CreateAccountRequest;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class VaAccountManagementService {

    private final AccountRepository    accountRepo;
    private final SnowflakeIdGenerator idGen;

    public VaAccountManagementService(AccountRepository accountRepo, SnowflakeIdGenerator idGen) {
        this.accountRepo = accountRepo;
        this.idGen       = idGen;
    }

    public AccountResponse createAccount(CreateAccountRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;

        VaAccount account = new VaAccount(
                idGen.generate(MasonXIdPrefix.VA_ACCOUNT.prefix()),
                mode,
                AccountRole.TENANT,
                req.orgId(),
                req.merchantId(),
                null,
                req.accountType(),
                req.asset().toUpperCase(),
                deriveAssetClass(req.asset()),
                deriveScale(req.asset()),
                deriveNormalBalance(req.accountType()),
                BigDecimal.ZERO,
                AccountStatus.ACTIVE
        );

        accountRepo.save(account);
        return toResponse(account);
    }

    public AccountResponse getAccount(String accountId, String merchantId) {
        VaAccount account = accountRepo.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (!account.merchantId().equals(merchantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return toResponse(account);
    }

    public PagedResult<AccountResponse> listAccounts(String merchantId, int page, int size) {
        long total = accountRepo.countTenantAccountsByMerchant(merchantId);
        List<AccountResponse> content = accountRepo
                .findTenantAccountsByMerchant(merchantId, page, size)
                .stream().map(this::toResponse).toList();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedResult<>(content, page, size, total, totalPages);
    }

    // ── Derivation helpers ────────────────────────────────────────────────────

    private NormalBalance deriveNormalBalance(AccountType type) {
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

    private AccountResponse toResponse(VaAccount a) {
        return new AccountResponse(
                a.accountId(),
                a.mode().name(),
                a.accountType().name(),
                a.merchantId(),
                a.asset(),
                a.balance(),
                a.status().name()
        );
    }
}
