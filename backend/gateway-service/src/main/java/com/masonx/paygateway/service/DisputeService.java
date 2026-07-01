package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.dispute.DisputeProviderAdapter;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.util.StringUtils;
import com.masonx.paygateway.domain.dispute.*;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.storage.FileStorageService;
import com.masonx.paygateway.web.dto.DisputeEvidenceFileResponse;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;
import com.masonx.paygateway.web.dto.DisputeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf");

    private final DisputeRepository disputeRepository;
    private final DisputeEvidenceFileRepository evidenceFileRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final FileStorageService storageService;
    private final ProviderAccountService providerAccountService;
    private final GatewayIdService gatewayIdService;
    private final ObjectMapper objectMapper;
    private final Map<String, DisputeProviderAdapter> adapters;

    public DisputeService(DisputeRepository disputeRepository,
                          DisputeEvidenceFileRepository evidenceFileRepository,
                          PaymentIntentRepository paymentIntentRepository,
                          FileStorageService storageService,
                          ProviderAccountService providerAccountService,
                          GatewayIdService gatewayIdService,
                          ObjectMapper objectMapper,
                          List<DisputeProviderAdapter> adapterList) {
        this.disputeRepository = disputeRepository;
        this.evidenceFileRepository = evidenceFileRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.storageService = storageService;
        this.providerAccountService = providerAccountService;
        this.gatewayIdService = gatewayIdService;
        this.objectMapper = objectMapper;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(DisputeProviderAdapter::providerName, Function.identity()));
    }

    // ── Ingest from provider webhooks ──────────────────────────────────────────

    @Transactional
    public void ingestDispute(IngestDisputeCommand cmd) {
        Optional<Dispute> existing = disputeRepository.findByProviderDisputeId(cmd.providerDisputeId());
        if (existing.isPresent()) {
            Dispute d = existing.get();
            d.setStatus(cmd.status());
            if (cmd.resolvedAt() != null) d.setResolvedAt(cmd.resolvedAt());
            if (cmd.evidenceDueBy() != null) d.setEvidenceDueBy(cmd.evidenceDueBy());
            gatewayIdService.assignDispute(d);
            disputeRepository.save(d);
            log.info("Updated dispute {} status -> {}", cmd.providerDisputeId(), cmd.status());
            return;
        }

        UUID paymentIntentId = null;
        UUID merchantId = null;
        ApiKeyMode mode = ApiKeyMode.LIVE;

        if (cmd.providerPaymentId() != null) {
            Optional<PaymentIntent> pi = paymentIntentRepository.findByProviderPaymentId(cmd.providerPaymentId());
            if (pi.isPresent()) {
                paymentIntentId = pi.get().getId();
                merchantId = pi.get().getMerchantId();
                mode = pi.get().getMode();
            } else {
                log.warn("Dispute {} arrived but no PaymentIntent found for providerPaymentId={}",
                        cmd.providerDisputeId(), cmd.providerPaymentId());
            }
        }

        Dispute dispute = new Dispute();
        dispute.setMerchantId(merchantId);
        dispute.setPaymentIntentId(paymentIntentId);
        dispute.setProvider(cmd.provider());
        dispute.setProviderDisputeId(cmd.providerDisputeId());
        dispute.setProviderChargeId(cmd.providerPaymentId());
        dispute.setStatus(cmd.status());
        dispute.setReason(cmd.reason());
        dispute.setAmount(cmd.amount());
        dispute.setCurrency(cmd.currency());
        dispute.setEvidenceDueBy(cmd.evidenceDueBy());
        dispute.setMode(mode);
        if (cmd.resolvedAt() != null) dispute.setResolvedAt(cmd.resolvedAt());
        gatewayIdService.assignDispute(dispute);
        disputeRepository.save(dispute);
        log.info("Created dispute {} for provider={} status={}", cmd.providerDisputeId(), cmd.provider(), cmd.status());
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<DisputeResponse> list(UUID merchantId, String modeStr, DisputeStatus status, Pageable pageable) {
        ApiKeyMode mode = parseMode(modeStr);
        Page<Dispute> page = (status != null)
                ? disputeRepository.findByMerchantIdAndModeAndStatusOrderByCreatedAtDesc(merchantId, mode, status, pageable)
                : disputeRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode, pageable);
        return page.map(d -> toResponse(d, List.of()));
    }

    private ApiKeyMode parseMode(String modeStr) {
        if (StringUtils.hasText(modeStr) && "TEST".equalsIgnoreCase(modeStr)) return ApiKeyMode.TEST;
        return ApiKeyMode.LIVE;
    }

    @Transactional(readOnly = true)
    public DisputeResponse get(UUID merchantId, String disputeId) {
        Dispute dispute = load(merchantId, disputeId);
        List<DisputeEvidenceFileResponse> files = evidenceFileRepository
                .findAllByDisputeId(dispute.getId()).stream()
                .map(f -> DisputeEvidenceFileResponse.from(f, storageService.getServeUrl(f.getFileKey())))
                .toList();
        return toResponse(dispute, files);
    }

    // ── File upload ────────────────────────────────────────────────────────────

    public DisputeEvidenceFileResponse uploadEvidenceFile(UUID merchantId, String disputeId,
                                                           MultipartFile file) {
        Dispute dispute = load(merchantId, disputeId);

        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported file type: " + contentType + ". Allowed: images (JPEG/PNG/GIF/WebP) and PDF.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File exceeds maximum allowed size of 10 MB.");
        }

        String ext = extensionFor(contentType);
        String keyPath = "disputes/" + merchantId + "/" + dispute.getId() + "/" + UUID.randomUUID() + ext;

        try {
            storageService.store(keyPath, file.getInputStream(), file.getSize(), contentType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store evidence file", e);
        }

        DisputeEvidenceFile ef = new DisputeEvidenceFile();
        ef.setDisputeId(dispute.getId());
        ef.setMerchantId(merchantId);
        ef.setFileKey(keyPath);
        ef.setFileName(file.getOriginalFilename());
        ef.setContentType(contentType);
        ef.setSizeBytes(file.getSize());
        gatewayIdService.assignDisputeEvidenceFile(ef);
        evidenceFileRepository.save(ef);

        return DisputeEvidenceFileResponse.from(ef, storageService.getServeUrl(keyPath));
    }

    // ── Evidence submission ────────────────────────────────────────────────────

    public DisputeResponse submitEvidence(UUID merchantId, String disputeId,
                                          DisputeEvidenceRequest req) {
        Dispute dispute = load(merchantId, disputeId);

        if (dispute.getSubmittedAt() != null) {
            throw new IllegalStateException("Evidence has already been submitted for this dispute.");
        }

        DisputeProviderAdapter adapter = adapters.get(dispute.getProvider());
        if (adapter == null) {
            throw new IllegalArgumentException("No dispute adapter for provider: " + dispute.getProvider());
        }

        ProviderCredentials creds = providerAccountService.resolveCredentials(
                merchantId,
                com.masonx.paygateway.domain.payment.PaymentProvider.valueOf(dispute.getProvider()),
                dispute.getMode());

        adapter.submitEvidence(dispute.getProviderDisputeId(), req, creds);

        // Record what was submitted
        try {
            dispute.setEvidenceTextJson(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            log.warn("Failed to serialize evidence text for dispute {}", disputeId);
        }
        dispute.setSubmittedAt(Instant.now());
        dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        disputeRepository.save(dispute);

        return get(merchantId, dispute.getExternalId() != null ? dispute.getExternalId() : dispute.getId().toString());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Dispute load(UUID merchantId, String disputeId) {
        try {
            UUID id = UUID.fromString(disputeId);
            return disputeRepository.findByIdAndMerchantId(id, merchantId)
                    .orElseThrow(() -> new IllegalArgumentException("Dispute not found"));
        } catch (IllegalArgumentException ignored) {
            return disputeRepository.findByExternalIdAndMerchantId(disputeId, merchantId)
                    .orElseThrow(() -> new IllegalArgumentException("Dispute not found"));
        }
    }

    private DisputeResponse toResponse(Dispute dispute, List<DisputeEvidenceFileResponse> files) {
        String paymentIntentExternalId = null;
        if (dispute.getPaymentIntentId() != null) {
            paymentIntentExternalId = paymentIntentRepository.findById(dispute.getPaymentIntentId())
                    .filter(intent -> intent.getMerchantId().equals(dispute.getMerchantId()))
                    .map(PaymentIntent::getExternalId)
                    .orElse(null);
        }
        return DisputeResponse.from(dispute, files, paymentIntentExternalId);
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg"       -> ".jpg";
            case "image/png"        -> ".png";
            case "image/gif"        -> ".gif";
            case "image/webp"       -> ".webp";
            case "application/pdf"  -> ".pdf";
            default                 -> "";
        };
    }

    // ── Command record ─────────────────────────────────────────────────────────

    public record IngestDisputeCommand(
            String provider,
            String providerDisputeId,
            String providerPaymentId,
            DisputeStatus status,
            DisputeReason reason,
            long amount,
            String currency,
            Instant evidenceDueBy,
            Instant resolvedAt
    ) {}
}
