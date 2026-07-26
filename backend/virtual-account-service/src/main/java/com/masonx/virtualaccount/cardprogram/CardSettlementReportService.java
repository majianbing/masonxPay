package com.masonx.virtualaccount.cardprogram;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.cardprogram.dto.CardSettlementReportLineResponse;
import com.masonx.virtualaccount.cardprogram.dto.CardSettlementReportResponse;
import com.masonx.virtualaccount.cardprogram.dto.IngestCardSettlementReportRequest;
import com.masonx.virtualaccount.domain.CardClearingEventRepository;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardSettlementReportRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportLineStatus;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportStatus;
import com.masonx.virtualaccount.domain.po.CardClearingEvent;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.CardSettlementReport;
import com.masonx.virtualaccount.domain.po.CardSettlementReportLine;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CardSettlementReportService {

    private final CardProgramRepository cardPrograms;
    private final CardClearingEventRepository clearingEvents;
    private final CardSettlementReportRepository reports;
    private final VirtualCardRepository virtualCards;
    private final SnowflakeIdGenerator idGen;

    public CardSettlementReportService(CardProgramRepository cardPrograms,
                                       CardClearingEventRepository clearingEvents,
                                       CardSettlementReportRepository reports,
                                       VirtualCardRepository virtualCards,
                                       SnowflakeIdGenerator idGen) {
        this.cardPrograms = cardPrograms;
        this.clearingEvents = clearingEvents;
        this.reports = reports;
        this.virtualCards = virtualCards;
        this.idGen = idGen;
    }

    @Transactional
    public CardSettlementReportResponse ingest(IngestCardSettlementReportRequest req) {
        Mode mode = req.mode() != null ? req.mode() : Mode.TEST;
        CardProgram program = cardPrograms
                .findByIdForMerchant(req.programId(), req.merchantId(), mode)
                .orElseThrow(() -> notFound("Card program not found"));
        CardSettlementReport existing = reports
                .findByReportRef(req.merchantId(), mode, program.issuerPartnerId(), req.reportRef())
                .orElse(null);
        if (existing != null) {
            List<CardSettlementReportLineResponse> existingLines = reports
                    .findLines(existing.reportId(), req.merchantId(), mode)
                    .stream()
                    .map(CardSettlementReportLineResponse::from)
                    .toList();
            return CardSettlementReportResponse.from(existing, existingLines);
        }

        String reportId = idGen.generate(MasonXIdPrefix.CARD_SETTLEMENT_REPORT.prefix());
        List<CardSettlementReportLine> lines = new ArrayList<>();
        BigDecimal matchedAmount = BigDecimal.ZERO;
        BigDecimal exceptionAmount = BigDecimal.ZERO;
        int exceptionCount = 0;

        for (IngestCardSettlementReportRequest.Line lineReq : req.lines()) {
            CardSettlementReportLine line = reconcileLine(reportId, req.merchantId(), mode, program, lineReq);
            lines.add(line);
            if (line.status() == CardSettlementReportLineStatus.MATCHED) {
                matchedAmount = matchedAmount.add(line.amount());
            } else {
                exceptionAmount = exceptionAmount.add(line.amount());
                exceptionCount++;
            }
        }

        BigDecimal totalAmount = req.lines().stream()
                .map(IngestCardSettlementReportRequest.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CardSettlementReportStatus status = exceptionCount == 0
                ? CardSettlementReportStatus.MATCHED
                : CardSettlementReportStatus.EXCEPTION;
        CardSettlementReport report = new CardSettlementReport(
                reportId,
                req.merchantId(),
                mode,
                program.programId(),
                program.issuerPartnerId(),
                req.reportRef(),
                req.settlementDate(),
                req.currency().toUpperCase(),
                totalAmount,
                matchedAmount,
                exceptionAmount,
                lines.size(),
                exceptionCount,
                status,
                Instant.now());
        reports.insertReport(report);
        lines.forEach(reports::insertLine);
        return toResponse(report, lines);
    }

    public PagedResult<CardSettlementReportResponse> list(String merchantId, Mode mode, String programId,
                                                         int page, int size) {
        long total = reports.countByProgram(merchantId, mode, programId);
        List<CardSettlementReportResponse> content = reports
                .findByProgram(merchantId, mode, programId, page, size)
                .stream()
                .map(report -> CardSettlementReportResponse.from(report, List.of()))
                .toList();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedResult<>(content, page, size, total, totalPages);
    }

    public CardSettlementReportResponse get(String reportId, String merchantId, Mode mode) {
        CardSettlementReport report = reports.findReport(reportId, merchantId, mode)
                .orElseThrow(() -> notFound("Card settlement report not found"));
        List<CardSettlementReportLineResponse> lines = reports.findLines(reportId, merchantId, mode)
                .stream()
                .map(CardSettlementReportLineResponse::from)
                .toList();
        return CardSettlementReportResponse.from(report, lines);
    }

    private CardSettlementReportLine reconcileLine(String reportId, String merchantId, Mode mode,
                                                  CardProgram program,
                                                  IngestCardSettlementReportRequest.Line lineReq) {
        CardClearingEvent clearing = clearingEvents.findByRailPaymentId(lineReq.railPaymentId()).orElse(null);
        if (clearing == null) {
            return line(reportId, merchantId, mode, program.programId(), lineReq, null, null,
                    CardSettlementReportLineStatus.CLEARING_NOT_FOUND, lineReq.amount(),
                    "No MasonXPay clearing event for railPaymentId=" + lineReq.railPaymentId());
        }
        VirtualCard card = virtualCards.findById(clearing.cardId()).orElse(null);
        if (card == null || !program.programId().equals(card.programId())) {
            return line(reportId, merchantId, mode, program.programId(), lineReq, clearing, card,
                    CardSettlementReportLineStatus.PROGRAM_MISMATCH, lineReq.amount().subtract(clearing.amount()).abs(),
                    "Matched clearing belongs to a different card program");
        }
        if (!lineReq.movementType().equals(clearing.movementType())) {
            return line(reportId, merchantId, mode, program.programId(), lineReq, clearing, card,
                    CardSettlementReportLineStatus.MOVEMENT_TYPE_MISMATCH, lineReq.amount().subtract(clearing.amount()).abs(),
                    "Issuer movement type does not match MasonXPay clearing event");
        }
        if (!lineReq.currency().equalsIgnoreCase(clearing.currency())) {
            return line(reportId, merchantId, mode, program.programId(), lineReq, clearing, card,
                    CardSettlementReportLineStatus.CURRENCY_MISMATCH, lineReq.amount().subtract(clearing.amount()).abs(),
                    "Issuer currency does not match MasonXPay clearing event");
        }
        if (lineReq.amount().compareTo(clearing.amount()) != 0) {
            return line(reportId, merchantId, mode, program.programId(), lineReq, clearing, card,
                    CardSettlementReportLineStatus.AMOUNT_MISMATCH, lineReq.amount().subtract(clearing.amount()).abs(),
                    "Issuer amount does not match MasonXPay clearing event");
        }
        return line(reportId, merchantId, mode, program.programId(), lineReq, clearing, card,
                CardSettlementReportLineStatus.MATCHED, BigDecimal.ZERO, null);
    }

    private CardSettlementReportLine line(String reportId, String merchantId, Mode mode, String programId,
                                          IngestCardSettlementReportRequest.Line req,
                                          CardClearingEvent clearing, VirtualCard card,
                                          CardSettlementReportLineStatus status,
                                          BigDecimal mismatchAmount,
                                          String detail) {
        return new CardSettlementReportLine(
                idGen.generate(MasonXIdPrefix.CARD_SETTLEMENT_REPORT_LINE.prefix()),
                reportId,
                merchantId,
                mode,
                programId,
                req.railPaymentId(),
                req.issuerTransactionId(),
                req.movementType(),
                req.amount(),
                req.currency().toUpperCase(),
                clearing != null ? clearing.clearingEventId() : null,
                card != null ? card.cardId() : null,
                status,
                mismatchAmount != null ? mismatchAmount : BigDecimal.ZERO,
                detail,
                Instant.now());
    }

    private CardSettlementReportResponse toResponse(CardSettlementReport report,
                                                   List<CardSettlementReportLine> lines) {
        return CardSettlementReportResponse.from(report,
                lines.stream().map(CardSettlementReportLineResponse::from).toList());
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }
}
