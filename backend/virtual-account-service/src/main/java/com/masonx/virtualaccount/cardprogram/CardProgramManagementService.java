package com.masonx.virtualaccount.cardprogram;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.cardprogram.dto.*;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardholderRepository;
import com.masonx.virtualaccount.domain.IssuerPartnerRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.Cardholder;
import com.masonx.virtualaccount.domain.po.IssuerPartner;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class CardProgramManagementService {

    private final IssuerPartnerRepository issuerPartners;
    private final CardProgramRepository cardPrograms;
    private final CardholderRepository cardholders;
    private final SnowflakeIdGenerator idGen;

    public CardProgramManagementService(IssuerPartnerRepository issuerPartners,
                                        CardProgramRepository cardPrograms,
                                        CardholderRepository cardholders,
                                        SnowflakeIdGenerator idGen) {
        this.issuerPartners = issuerPartners;
        this.cardPrograms = cardPrograms;
        this.cardholders = cardholders;
        this.idGen = idGen;
    }

    public IssuerPartnerResponse createIssuerPartner(CreateIssuerPartnerRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;
        IssuerPartnerStatus status = req.status() != null ? req.status() : IssuerPartnerStatus.DRAFT;
        IssuerPartner partner = new IssuerPartner(
                idGen.generate(MasonXIdPrefix.ISSUER_PARTNER.prefix()),
                req.merchantId(),
                mode,
                req.name(),
                req.adapterType(),
                status,
                req.credentialsRef(),
                jsonOrEmpty(req.configJson()),
                req.webhookSecretRef(),
                req.externalProgramId(),
                req.externalFundingSourceId(),
                Instant.now(),
                Instant.now());
        issuerPartners.save(partner);
        return toResponse(partner);
    }

    public IssuerPartnerResponse getIssuerPartner(String issuerPartnerId, String merchantId, Mode mode) {
        return issuerPartners.findByIdForMerchant(issuerPartnerId, merchantId, mode)
                .map(this::toResponse)
                .orElseThrow(() -> notFound("Issuer partner not found"));
    }

    public PagedResult<IssuerPartnerResponse> listIssuerPartners(String merchantId, Mode mode, int page, int size) {
        long total = issuerPartners.countByMerchant(merchantId, mode);
        List<IssuerPartnerResponse> content = issuerPartners.findByMerchant(merchantId, mode, page, size)
                .stream().map(this::toResponse).toList();
        return page(content, page, size, total);
    }

    public CardProgramResponse createCardProgram(CreateCardProgramRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;
        CardProgramStatus status = req.status() != null ? req.status() : CardProgramStatus.DRAFT;
        IssuerPartner partner = issuerPartners
                .findByIdForMerchant(req.issuerPartnerId(), req.merchantId(), mode)
                .orElseThrow(() -> notFound("Issuer partner not found"));
        if (status == CardProgramStatus.ACTIVE && partner.status() != IssuerPartnerStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active card program requires active issuer partner");
        }

        CardProgram program = new CardProgram(
                idGen.generate(MasonXIdPrefix.CARD_PROGRAM.prefix()),
                req.merchantId(),
                mode,
                req.issuerPartnerId(),
                req.name(),
                req.currency().toUpperCase(),
                jsonOrEmpty(req.binRangeMetadata()),
                status,
                req.systemOfRecord(),
                req.fundingModel(),
                jsonOrEmpty(req.featureFlagsJson()),
                jsonOrEmpty(req.defaultControlsJson()),
                jsonOrEmpty(req.settlementModelJson()),
                req.feeScheduleId(),
                req.externalProgramId(),
                req.externalFundingSourceId(),
                Instant.now(),
                Instant.now());
        cardPrograms.save(program);
        return toResponse(program);
    }

    public CardProgramResponse getCardProgram(String programId, String merchantId, Mode mode) {
        return cardPrograms.findByIdForMerchant(programId, merchantId, mode)
                .map(this::toResponse)
                .orElseThrow(() -> notFound("Card program not found"));
    }

    public PagedResult<CardProgramResponse> listCardPrograms(String merchantId, Mode mode, int page, int size) {
        long total = cardPrograms.countByMerchant(merchantId, mode);
        List<CardProgramResponse> content = cardPrograms.findByMerchant(merchantId, mode, page, size)
                .stream().map(this::toResponse).toList();
        return page(content, page, size, total);
    }

    public CardholderResponse createCardholder(CreateCardholderRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;
        CardholderKycStatus kycStatus = req.kycStatus() != null
                ? req.kycStatus()
                : CardholderKycStatus.PENDING;
        Cardholder cardholder = new Cardholder(
                idGen.generate(MasonXIdPrefix.CARDHOLDER.prefix()),
                req.merchantId(),
                mode,
                req.type(),
                req.externalIssuerCardholderId(),
                kycStatus,
                req.displayName(),
                req.displayRef(),
                req.profileRef(),
                Instant.now(),
                Instant.now());
        cardholders.save(cardholder);
        return toResponse(cardholder);
    }

    public CardholderResponse getCardholder(String cardholderId, String merchantId, Mode mode) {
        return cardholders.findByIdForMerchant(cardholderId, merchantId, mode)
                .map(this::toResponse)
                .orElseThrow(() -> notFound("Cardholder not found"));
    }

    public PagedResult<CardholderResponse> listCardholders(String merchantId, Mode mode, int page, int size) {
        long total = cardholders.countByMerchant(merchantId, mode);
        List<CardholderResponse> content = cardholders.findByMerchant(merchantId, mode, page, size)
                .stream().map(this::toResponse).toList();
        return page(content, page, size, total);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static String jsonOrEmpty(String json) {
        return json != null ? json : "{}";
    }

    private static <T> PagedResult<T> page(List<T> content, int page, int size, long total) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedResult<>(content, page, size, total, totalPages);
    }

    private IssuerPartnerResponse toResponse(IssuerPartner partner) {
        return new IssuerPartnerResponse(
                partner.issuerPartnerId(),
                partner.merchantId(),
                partner.mode(),
                partner.name(),
                partner.adapterType(),
                partner.status(),
                partner.credentialsRef(),
                partner.configJson(),
                partner.webhookSecretRef(),
                partner.externalProgramId(),
                partner.externalFundingSourceId(),
                partner.createdAt(),
                partner.updatedAt());
    }

    private CardProgramResponse toResponse(CardProgram program) {
        return new CardProgramResponse(
                program.programId(),
                program.merchantId(),
                program.mode(),
                program.issuerPartnerId(),
                program.name(),
                program.currency(),
                program.binRangeMetadata(),
                program.status(),
                program.systemOfRecord(),
                program.fundingModel(),
                program.featureFlagsJson(),
                program.defaultControlsJson(),
                program.settlementModelJson(),
                program.feeScheduleId(),
                program.externalProgramId(),
                program.externalFundingSourceId(),
                program.createdAt(),
                program.updatedAt());
    }

    private CardholderResponse toResponse(Cardholder cardholder) {
        return new CardholderResponse(
                cardholder.cardholderId(),
                cardholder.merchantId(),
                cardholder.mode(),
                cardholder.type(),
                cardholder.externalIssuerCardholderId(),
                cardholder.kycStatus(),
                cardholder.displayName(),
                cardholder.displayRef(),
                cardholder.profileRef(),
                cardholder.createdAt(),
                cardholder.updatedAt());
    }
}
