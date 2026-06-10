package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.audit.AuditAction;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.connector.ProviderAccountCapabilityRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.simulator.ProviderSimulatorProperties;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
import com.masonx.paygateway.web.dto.ReorderConnectorsRequest;
import com.masonx.paygateway.web.dto.ProviderAccountResponse;
import com.masonx.paygateway.web.dto.UpdateProviderAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class ProviderAccountService {

    private final ProviderAccountRepository repo;
    private final ProviderAccountCapabilityRepository capabilityRepository;
    private final CredentialsCodec codec;
    private final ProviderSimulatorProperties simulatorProperties;
    private final MerchantAuditLogService auditLogService;

    public ProviderAccountService(ProviderAccountRepository repo,
                                  ProviderAccountCapabilityRepository capabilityRepository,
                                  CredentialsCodec codec,
                                  ProviderSimulatorProperties simulatorProperties,
                                  MerchantAuditLogService auditLogService) {
        this.repo = repo;
        this.capabilityRepository = capabilityRepository;
        this.codec = codec;
        this.simulatorProperties = simulatorProperties;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<ProviderAccountResponse> list(UUID merchantId, ApiKeyMode mode) {
        return repo.findAllByMerchantIdAndModeOrderByDisplayOrderAscCreatedAtDesc(merchantId, mode)
                .stream()
                .map(a -> ProviderAccountResponse.from(a, codec.clientKeyFor(a)))
                .toList();
    }

    public ProviderAccountResponse create(UUID merchantId, CreateProviderAccountRequest req) {
        PaymentProvider provider = PaymentProvider.valueOf(req.provider().toUpperCase());
        ApiKeyMode mode = ApiKeyMode.valueOf(req.mode().toUpperCase());
        validateProviderMode(provider, mode);

        if (req.primary()) {
            repo.clearPrimaryForProvider(merchantId, provider, mode);
        }

        ProviderAccount account = new ProviderAccount();
        account.setMerchantId(merchantId);
        account.setProvider(provider);
        account.setMode(mode);
        account.setLabel(req.label());
        account.setPrimary(req.primary());
        account.setWeight(req.weight() > 0 ? req.weight() : 1);
        account.setFixedFeeCents(Math.max(0, req.fixedFeeCents()));
        account.setRateBps(Math.max(0, req.rateBps()));

        ProviderCredentials creds = codec.fromRequest(provider, req, mode);
        codec.encode(creds, account);

        ProviderAccount saved = repo.save(account);
        seedDefaultCapabilities(saved);
        auditLogService.record(merchantId, AuditAction.CONNECTOR_CREATED,
                "CONNECTOR", saved.getId().toString(), provider.name() + " - " + req.label(),
                Map.of("provider", provider.name(), "label", req.label(), "mode", mode.name()));
        return ProviderAccountResponse.from(saved, codec.clientKeyFor(saved));
    }

    public ProviderAccountResponse update(UUID merchantId, UUID accountId, UpdateProviderAccountRequest req) {
        ProviderAccount account = loadOwned(merchantId, accountId);

        if (req.label() != null && !req.label().isBlank()) {
            account.setLabel(req.label());
        }
        if (req.status() != null) {
            account.setStatus(ProviderAccountStatus.valueOf(req.status().toUpperCase()));
        }
        if (req.weight() != null && req.weight() > 0) {
            account.setWeight(req.weight());
        }
        if (req.fixedFeeCents() != null && req.fixedFeeCents() >= 0) {
            account.setFixedFeeCents(req.fixedFeeCents());
        }
        if (req.rateBps() != null && req.rateBps() >= 0) {
            account.setRateBps(req.rateBps());
        }

        ProviderAccountResponse updated = ProviderAccountResponse.from(repo.save(account), codec.clientKeyFor(account));
        auditLogService.record(merchantId, AuditAction.CONNECTOR_UPDATED,
                "CONNECTOR", accountId.toString(), account.getProvider().name() + " - " + account.getLabel(),
                Map.of("provider", account.getProvider().name(), "label", account.getLabel()));
        return updated;
    }

    public void delete(UUID merchantId, UUID accountId) {
        ProviderAccount account = loadOwned(merchantId, accountId);
        String label = account.getProvider().name() + " - " + account.getLabel();
        repo.delete(account);
        auditLogService.record(merchantId, AuditAction.CONNECTOR_DELETED,
                "CONNECTOR", accountId.toString(), label,
                Map.of("provider", account.getProvider().name(), "label", account.getLabel()));
    }

    public ProviderAccountResponse setPrimary(UUID merchantId, UUID accountId) {
        ProviderAccount account = loadOwned(merchantId, accountId);
        repo.clearPrimaryForProvider(merchantId, account.getProvider(), account.getMode());
        account.setPrimary(true);
        return ProviderAccountResponse.from(repo.save(account), codec.clientKeyFor(account));
    }

    /**
     * Loads fully-typed credentials for a specific connector account.
     * Used by charge/refund paths.
     */
    @Transactional(readOnly = true)
    public ProviderCredentials loadCredentials(UUID accountId) {
        ProviderAccount account = repo.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Connector account not found: " + accountId));
        return codec.decode(account);
    }

    /**
     * Legacy helper — resolves the primary connector key by brand.
     * Still used for flows that haven't stored connectorAccountId yet.
     */
    @Transactional(readOnly = true)
    public ProviderCredentials resolveCredentials(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        return repo.findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
                        merchantId, provider, mode, ProviderAccountStatus.ACTIVE)
                .map(codec::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "No active primary connector for " + provider + " / " + mode));
    }

    public void reorder(UUID merchantId, List<ReorderConnectorsRequest.BrandOrder> items) {
        for (ReorderConnectorsRequest.BrandOrder item : items) {
            PaymentProvider provider = PaymentProvider.valueOf(item.provider().toUpperCase());
            repo.updateDisplayOrderByMerchantIdAndProvider(merchantId, provider, item.displayOrder());
        }
    }

    private ProviderAccount loadOwned(UUID merchantId, UUID accountId) {
        return repo.findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
    }

    private void validateProviderMode(PaymentProvider provider, ApiKeyMode mode) {
        if (provider != PaymentProvider.SIMULATOR) {
            return;
        }
        if (!simulatorProperties.isEnabled()) {
            throw new IllegalStateException("Mason Simulator provider is disabled");
        }
        if (mode != ApiKeyMode.TEST) {
            throw new IllegalArgumentException("Mason Simulator provider is only available in TEST mode");
        }
    }

    private void seedDefaultCapabilities(ProviderAccount account) {
        ProviderAccountCapability card = new ProviderAccountCapability();
        card.setMerchantId(account.getMerchantId());
        card.setProviderAccountId(account.getId());
        card.setPaymentMethodType("card");
        card.setSupportsProviderToken(true);
        card.setSupportsVaultToken(false);
        card.setSupportsNetworkToken(false);
        card.setSupportsManualCapture(account.getProvider() != PaymentProvider.MOLLIE);
        card.setSupportsRedirect(account.getProvider() == PaymentProvider.MOLLIE);
        card.setSupports3ds(account.getProvider() == PaymentProvider.STRIPE
                || account.getProvider() == PaymentProvider.MOLLIE
                || account.getProvider() == PaymentProvider.SIMULATOR);
        card.setEnabled(true);
        capabilityRepository.save(card);
    }
}
