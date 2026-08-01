package com.masonx.virtualaccount.fee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.feeengine.FeeAssessment;
import com.masonx.feeengine.FeeContext;
import com.masonx.feeengine.FeeContextField;
import com.masonx.feeengine.FeeContextSchema;
import com.masonx.feeengine.FeeEngine;
import com.masonx.feeengine.FeeFieldType;
import com.masonx.feeengine.FeeLine;
import com.masonx.feeengine.FeeRule;
import com.masonx.feeengine.FeeRuleMatch;
import com.masonx.feeengine.FeeRuleValidationException;
import com.masonx.feeengine.FeeScheduleVersion;
import com.masonx.virtualaccount.domain.PrepaidFeeAssessmentRepository;
import com.masonx.virtualaccount.domain.PrepaidFeeScheduleRepository;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import com.masonx.virtualaccount.domain.po.PrepaidFeeSchedule;
import com.masonx.virtualaccount.domain.po.PrepaidFeeScheduleVersion;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PrepaidFeeAssessmentService {

    private static final TypeReference<List<FeeRule>> FEE_RULE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final Set<String> FORBIDDEN_CONTEXT_KEYS = Set.of(
            "pan", "cardPan", "rawPan", "cvv", "cvc", "trackData", "rawPayload",
            "signatureHeader", "authorizationHeader", "apiKey", "secret", "token");
    private static final FeeContextSchema PREPAID_SCHEMA = FeeContextSchema.of(List.of(
            new FeeContextField("merchantId", FeeFieldType.STRING, true),
            new FeeContextField("mode", FeeFieldType.STRING, true),
            new FeeContextField("eventType", FeeFieldType.STRING, true),
            new FeeContextField("eventId", FeeFieldType.STRING, true),
            new FeeContextField("programId", FeeFieldType.STRING, false),
            new FeeContextField("cardId", FeeFieldType.STRING, false),
            new FeeContextField("cardholderId", FeeFieldType.STRING, false),
            new FeeContextField("issuerPartnerId", FeeFieldType.STRING, false),
            new FeeContextField("bin", FeeFieldType.STRING, false),
            new FeeContextField("channel", FeeFieldType.STRING, false),
            new FeeContextField("rail", FeeFieldType.STRING, false),
            new FeeContextField("network", FeeFieldType.STRING, false),
            new FeeContextField("fundingWalletId", FeeFieldType.STRING, false),
            new FeeContextField("cardCurrency", FeeFieldType.STRING, false),
            new FeeContextField("accountCurrency", FeeFieldType.STRING, false),
            new FeeContextField("transactionCurrency", FeeFieldType.STRING, false),
            new FeeContextField("purchaseCurrency", FeeFieldType.STRING, false),
            new FeeContextField("amountUsd", FeeFieldType.DECIMAL, false),
            new FeeContextField("amount", FeeFieldType.DECIMAL, false),
            new FeeContextField("transactionAmount", FeeFieldType.DECIMAL, false),
            new FeeContextField("purchaseAmount", FeeFieldType.DECIMAL, false),
            new FeeContextField("isCrossCurrency", FeeFieldType.BOOLEAN, false)
    ));

    private final PrepaidFeeScheduleRepository scheduleRepository;
    private final PrepaidFeeAssessmentRepository assessmentRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final FeeEngine feeEngine;
    private final ObjectMapper objectMapper;

    public PrepaidFeeAssessmentService(PrepaidFeeScheduleRepository scheduleRepository,
                                       PrepaidFeeAssessmentRepository assessmentRepository,
                                       SnowflakeIdGenerator idGenerator,
                                       FeeEngine feeEngine,
                                       ObjectMapper objectMapper) {
        this.scheduleRepository = scheduleRepository;
        this.assessmentRepository = assessmentRepository;
        this.idGenerator = idGenerator;
        this.feeEngine = feeEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PrepaidFeeSchedule createSchedule(CreatePrepaidFeeScheduleCommand command) {
        requireText(command.merchantId(), "merchantId");
        requireText(command.name(), "name");
        Mode mode = command.mode() != null ? command.mode() : Mode.TEST;
        PrepaidFeeScheduleStatus status = command.status() != null ? command.status() : PrepaidFeeScheduleStatus.DRAFT;
        rejectLiveActivation(mode, status);
        PrepaidFeeSchedule schedule = new PrepaidFeeSchedule(
                idGenerator.generate(MasonXIdPrefix.FEE_SCHEDULE.prefix()),
                command.merchantId(),
                mode,
                blankToNull(command.programId()),
                blankToNull(command.bin()),
                blankToNull(command.channel()),
                command.name(),
                status,
                null,
                null);
        scheduleRepository.save(schedule);
        return schedule;
    }

    public PagedResult<PrepaidFeeSchedule> listSchedules(String merchantId, Mode mode, int page, int size) {
        requireText(merchantId, "merchantId");
        Mode scopedMode = requireMode(mode);
        int safePage = Math.max(page, 0);
        int cappedSize = Math.min(Math.max(size, 1), 100);
        long total = scheduleRepository.countForMerchant(merchantId, scopedMode);
        List<PrepaidFeeSchedule> content = scheduleRepository.listForMerchant(
                merchantId, scopedMode, safePage, cappedSize);
        return new PagedResult<>(content, safePage, cappedSize, total,
                (int) Math.ceil(total / (double) cappedSize));
    }

    public List<PrepaidFeeScheduleVersion> listVersions(String scheduleId, String merchantId, Mode mode) {
        requireText(scheduleId, "scheduleId");
        requireText(merchantId, "merchantId");
        Mode scopedMode = requireMode(mode);
        scheduleRepository.findByIdForMerchant(scheduleId, merchantId, scopedMode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fee schedule not found"));
        return scheduleRepository.listVersions(scheduleId, merchantId, scopedMode);
    }

    @Transactional
    public PrepaidFeeScheduleVersion publishVersion(PublishPrepaidFeeScheduleVersionCommand command) {
        requireText(command.scheduleId(), "scheduleId");
        requireText(command.merchantId(), "merchantId");
        if (command.version() <= 0) {
            throw badRequest("version must be positive");
        }
        Mode mode = requireMode(command.mode());
        PrepaidFeeScheduleStatus status = command.status() != null ? command.status() : PrepaidFeeScheduleStatus.ACTIVE;
        rejectLiveActivation(mode, status);
        scheduleRepository.findByIdForMerchant(command.scheduleId(), command.merchantId(), mode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fee schedule not found"));
        PrepaidFeeScheduleVersion version = new PrepaidFeeScheduleVersion(
                command.scheduleId(),
                command.merchantId(),
                mode,
                command.version(),
                status,
                requireCurrency(command.feeCurrency()),
                command.feeScale(),
                (command.roundingMode() != null ? command.roundingMode() : RoundingMode.HALF_UP).name(),
                command.effectiveFrom() != null ? command.effectiveFrom() : Instant.now(),
                command.effectiveTo(),
                toJson(command.rules() != null ? command.rules() : List.of()),
                toJson(command.metadata() != null ? command.metadata() : Map.of()),
                null,
                status == PrepaidFeeScheduleStatus.ACTIVE ? Instant.now() : null);
        scheduleRepository.saveVersion(version);
        return version;
    }

    @Transactional
    public Optional<PrepaidFeeAssessmentSnapshot> assessAndPersist(AssessPrepaidFeeCommand command) {
        requireText(command.merchantId(), "merchantId");
        requireText(command.eventType(), "eventType");
        requireText(command.eventId(), "eventId");
        Mode mode = requireMode(command.mode());
        Optional<PrepaidFeeAssessmentSnapshot> existing = assessmentRepository.findByEvent(
                command.merchantId(), mode, command.eventType(), command.eventId());
        if (existing.isPresent()) {
            return existing;
        }

        Instant asOf = command.occurredAt() != null ? command.occurredAt() : Instant.now();
        Optional<PrepaidFeeScheduleVersion> activeVersion = scheduleRepository.findActiveVersion(
                command.merchantId(),
                mode,
                blankToNull(command.programId()),
                blankToNull(command.bin()),
                blankToNull(command.channel()),
                asOf);
        if (activeVersion.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> context = sanitizedContext(command, mode);
        FeeScheduleVersion schedule = toEngineSchedule(activeVersion.get());
        FeeAssessment computed = assess(schedule, context);
        String assessmentId = idGenerator.generate(MasonXIdPrefix.FEE_ASSESSMENT.prefix());
        PrepaidFeeAssessment assessment = new PrepaidFeeAssessment(
                assessmentId,
                command.merchantId(),
                mode,
                command.eventType(),
                command.eventId(),
                blankToNull(command.programId()),
                blankToNull(command.cardId()),
                computed.scheduleId(),
                computed.scheduleVersion(),
                toJson(context),
                toJson(computed.matchedRules()),
                toJson(computed.visibleTotals()),
                toJson(computed.hiddenTotals()),
                null);
        List<PrepaidFeeAssessmentLine> lines = toLines(assessmentId, command.merchantId(), mode, computed.lines());
        PrepaidFeeAssessmentSnapshot snapshot = new PrepaidFeeAssessmentSnapshot(assessment, lines);
        boolean inserted = assessmentRepository.saveSnapshotIfAbsent(assessment, lines);
        if (!inserted) {
            return assessmentRepository.findByEvent(command.merchantId(), mode, command.eventType(), command.eventId());
        }
        return Optional.of(snapshot);
    }

    public FeeAssessment preview(PreviewPrepaidFeeCommand command) {
        requireText(command.merchantId(), "merchantId");
        Mode mode = requireMode(command.mode());
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> supplied = command.context() != null ? command.context() : Map.of();
        for (Map.Entry<String, Object> entry : supplied.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw badRequest("Fee context key must not be blank");
            }
            if (FORBIDDEN_CONTEXT_KEYS.contains(key)) {
                throw badRequest("Unsafe fee context key is not allowed: " + key);
            }
            if (!PREPAID_SCHEMA.contains(key)) {
                throw badRequest("Unknown prepaid fee context key: " + key);
            }
            context.put(key, normalizeValue(entry.getValue()));
        }
        context.put("merchantId", command.merchantId());
        context.put("mode", mode.name());
        FeeScheduleVersion schedule = new FeeScheduleVersion(
                command.scheduleId() != null && !command.scheduleId().isBlank()
                        ? command.scheduleId()
                        : "preview",
                command.version() > 0 ? command.version() : 1,
                requireCurrency(command.feeCurrency()),
                command.feeScale(),
                command.roundingMode() != null ? command.roundingMode() : RoundingMode.HALF_UP,
                command.rules() != null ? command.rules() : List.of(),
                command.metadata() != null ? command.metadata() : Map.of());
        return assess(schedule, context);
    }

    public PagedResult<PrepaidFeeAssessmentSnapshot> listAssessments(
            String merchantId, Mode mode, int page, int size) {
        requireText(merchantId, "merchantId");
        Mode scopedMode = requireMode(mode);
        int safePage = Math.max(page, 0);
        int cappedSize = Math.min(Math.max(size, 1), 100);
        long total = assessmentRepository.countForMerchant(merchantId, scopedMode);
        List<PrepaidFeeAssessmentSnapshot> content = assessmentRepository.listForMerchant(
                merchantId, scopedMode, safePage, cappedSize);
        return new PagedResult<>(content, safePage, cappedSize, total,
                (int) Math.ceil(total / (double) cappedSize));
    }

    private FeeAssessment assess(FeeScheduleVersion schedule, Map<String, Object> context) {
        try {
            return feeEngine.assess(schedule, PREPAID_SCHEMA, FeeContext.of(context));
        } catch (FeeRuleValidationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private FeeScheduleVersion toEngineSchedule(PrepaidFeeScheduleVersion version) {
        return new FeeScheduleVersion(
                version.scheduleId(),
                version.version(),
                version.feeCurrency(),
                version.feeScale(),
                RoundingMode.valueOf(version.roundingMode()),
                fromJson(version.rulesJson(), FEE_RULE_LIST),
                fromJson(version.metadataJson(), STRING_MAP));
    }

    private Map<String, Object> sanitizedContext(AssessPrepaidFeeCommand command, Mode mode) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        Map<String, Object> supplied = command.context() != null ? command.context() : Map.of();
        for (Map.Entry<String, Object> entry : supplied.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw badRequest("Fee context key must not be blank");
            }
            if (FORBIDDEN_CONTEXT_KEYS.contains(key)) {
                throw badRequest("Unsafe fee context key is not allowed: " + key);
            }
            if (!PREPAID_SCHEMA.contains(key)) {
                throw badRequest("Unknown prepaid fee context key: " + key);
            }
            sanitized.put(key, normalizeValue(entry.getValue()));
        }
        putIfPresent(sanitized, "programId", blankToNull(command.programId()));
        putIfPresent(sanitized, "cardId", blankToNull(command.cardId()));
        putIfPresent(sanitized, "bin", blankToNull(command.bin()));
        putIfPresent(sanitized, "channel", blankToNull(command.channel()));
        sanitized.put("merchantId", command.merchantId());
        sanitized.put("mode", mode.name());
        sanitized.put("eventType", command.eventType());
        sanitized.put("eventId", command.eventId());
        return sanitized;
    }

    private List<PrepaidFeeAssessmentLine> toLines(String assessmentId,
                                                  String merchantId,
                                                  Mode mode,
                                                  List<FeeLine> lines) {
        List<PrepaidFeeAssessmentLine> persisted = new ArrayList<>();
        for (FeeLine line : lines) {
            persisted.add(new PrepaidFeeAssessmentLine(
                    null,
                    assessmentId,
                    merchantId,
                    mode,
                    line.ruleId(),
                    line.ruleVersion(),
                    line.ruleName(),
                    line.componentId(),
                    line.name(),
                    line.visibility().name(),
                    line.currency(),
                    line.basisAmount(),
                    line.rawCalculatedAmount(),
                    line.amount(),
                    line.roundingMode().name(),
                    line.roundingScale(),
                    toJson(line.metadata()),
                    null));
        }
        return persisted;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(value.toString());
        }
        return value;
    }

    private static void putIfPresent(Map<String, Object> context, String key, String value) {
        if (value != null) {
            context.put(key, value);
        }
    }

    private static Mode requireMode(Mode mode) {
        if (mode == null) {
            throw badRequest("mode is required");
        }
        return mode;
    }

    private static String requireCurrency(String currency) {
        requireText(currency, "feeCurrency");
        return currency.trim().toUpperCase();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw badRequest(fieldName + " is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static void rejectLiveActivation(Mode mode, PrepaidFeeScheduleStatus status) {
        if (mode == Mode.LIVE && status == PrepaidFeeScheduleStatus.ACTIVE) {
            throw badRequest("LIVE fee schedule activation requires platform admin/compliance approval");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fee snapshot JSON", ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fee schedule JSON", ex);
        }
    }
}
