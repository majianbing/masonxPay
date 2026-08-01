package com.masonx.paygateway.fee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.feeengine.FeeAssessment;
import com.masonx.feeengine.FeeContext;
import com.masonx.feeengine.FeeContextField;
import com.masonx.feeengine.FeeContextSchema;
import com.masonx.feeengine.FeeEngine;
import com.masonx.feeengine.FeeFieldType;
import com.masonx.feeengine.FeeLine;
import com.masonx.feeengine.FeeRule;
import com.masonx.feeengine.FeeRuleValidationException;
import com.masonx.feeengine.FeeScheduleVersion;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class GatewayFeeAssessmentService implements GatewayFeeAssessmentPort {

    public static final String EVENT_PAYMENT_CONFIRM = "PAYMENT_CONFIRM";

    private static final TypeReference<List<FeeRule>> FEE_RULE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final Set<String> FORBIDDEN_CONTEXT_KEYS = Set.of(
            "pan", "cardPan", "rawPan", "cvv", "cvc", "trackData", "rawPayload",
            "signatureHeader", "authorizationHeader", "apiKey", "secret", "token");
    private static final FeeContextSchema GATEWAY_SCHEMA = FeeContextSchema.of(List.of(
            new FeeContextField("merchantId", FeeFieldType.STRING, true),
            new FeeContextField("mode", FeeFieldType.STRING, true),
            new FeeContextField("eventType", FeeFieldType.STRING, true),
            new FeeContextField("eventId", FeeFieldType.STRING, true),
            new FeeContextField("paymentIntentId", FeeFieldType.STRING, false),
            new FeeContextField("paymentRequestId", FeeFieldType.STRING, false),
            new FeeContextField("provider", FeeFieldType.STRING, false),
            new FeeContextField("connectorAccountId", FeeFieldType.STRING, false),
            new FeeContextField("paymentMethodType", FeeFieldType.STRING, false),
            new FeeContextField("currency", FeeFieldType.STRING, false),
            new FeeContextField("amount", FeeFieldType.DECIMAL, false),
            new FeeContextField("amountMinor", FeeFieldType.DECIMAL, false),
            new FeeContextField("captureMethod", FeeFieldType.STRING, false)
    ));

    private final GatewayFeeScheduleRepository scheduleRepository;
    private final GatewayFeeAssessmentRepository assessmentRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final FeeEngine feeEngine;
    private final ObjectMapper objectMapper;

    public GatewayFeeAssessmentService(GatewayFeeScheduleRepository scheduleRepository,
                                       GatewayFeeAssessmentRepository assessmentRepository,
                                       SnowflakeIdGenerator idGenerator,
                                       FeeEngine feeEngine,
                                       ObjectMapper objectMapper) {
        this.scheduleRepository = scheduleRepository;
        this.assessmentRepository = assessmentRepository;
        this.idGenerator = idGenerator;
        this.feeEngine = feeEngine;
        this.objectMapper = objectMapper;
    }

    @Override
    public void assessPaymentConfirm(PaymentIntent intent, PaymentRequest successfulAttempt) {
        if (intent == null) {
            return;
        }
        long amountMinor = successfulAttempt != null ? successfulAttempt.getAmount() : intent.getAmount();
        String currency = successfulAttempt != null ? successfulAttempt.getCurrency() : intent.getCurrency();
        String paymentMethodType = successfulAttempt != null
                ? successfulAttempt.getPaymentMethodType()
                : intent.getPaymentMethodType();
        UUID paymentRequestId = successfulAttempt != null ? successfulAttempt.getId() : null;
        assessAndPersist(new AssessGatewayFeeCommand(
                intent.getMerchantId(),
                intent.getMode(),
                EVENT_PAYMENT_CONFIRM,
                intent.getId(),
                intent.getId(),
                paymentRequestId,
                intent.getResolvedProvider(),
                intent.getConnectorAccountId(),
                paymentMethodType,
                currency,
                amountMinor,
                Map.of("captureMethod", intent.getCaptureMethod().name()),
                Instant.now()));
    }

    public Optional<GatewayFeeAssessmentSnapshot> assessAndPersist(AssessGatewayFeeCommand command) {
        requireNonNull(command.merchantId(), "merchantId");
        requireNonNull(command.mode(), "mode");
        requireText(command.eventType(), "eventType");
        requireNonNull(command.eventId(), "eventId");
        Optional<GatewayFeeAssessmentSnapshot> existing = assessmentRepository.findByEvent(
                command.merchantId(), command.mode(), command.eventType(), command.eventId());
        if (existing.isPresent()) {
            return existing;
        }

        Instant asOf = command.occurredAt() != null ? command.occurredAt() : Instant.now();
        Optional<GatewayFeeScheduleVersion> activeVersion = scheduleRepository.findActiveVersion(
                command.merchantId(),
                command.mode(),
                command.eventType(),
                command.provider(),
                command.connectorAccountId(),
                blankToNull(command.paymentMethodType()),
                asOf);
        if (activeVersion.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> context = sanitizedContext(command);
        FeeScheduleVersion schedule = toEngineSchedule(activeVersion.get());
        FeeAssessment computed = assess(schedule, context);
        String assessmentId = idGenerator.generate(MasonXIdPrefix.FEE_ASSESSMENT.prefix());
        GatewayFeeAssessment assessment = new GatewayFeeAssessment(
                assessmentId,
                command.merchantId(),
                command.mode(),
                command.eventType(),
                command.eventId(),
                command.paymentIntentId(),
                command.paymentRequestId(),
                command.provider(),
                command.connectorAccountId(),
                blankToNull(command.paymentMethodType()),
                computed.scheduleId(),
                computed.scheduleVersion(),
                toJson(context),
                toJson(computed.matchedRules()),
                toJson(computed.visibleTotals()),
                toJson(computed.hiddenTotals()),
                null);
        List<GatewayFeeAssessmentLine> lines = toLines(assessmentId, command.merchantId(), command.mode(), computed.lines());
        GatewayFeeAssessmentSnapshot snapshot = new GatewayFeeAssessmentSnapshot(assessment, lines);
        boolean inserted = assessmentRepository.saveSnapshotIfAbsent(assessment, lines);
        if (!inserted) {
            return assessmentRepository.findByEvent(command.merchantId(), command.mode(), command.eventType(), command.eventId());
        }
        return Optional.of(snapshot);
    }

    private FeeAssessment assess(FeeScheduleVersion schedule, Map<String, Object> context) {
        try {
            return feeEngine.assess(schedule, GATEWAY_SCHEMA, FeeContext.of(context));
        } catch (FeeRuleValidationException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private FeeScheduleVersion toEngineSchedule(GatewayFeeScheduleVersion version) {
        return new FeeScheduleVersion(
                version.scheduleId(),
                version.version(),
                requireCurrency(version.feeCurrency()),
                version.feeScale(),
                RoundingMode.valueOf(version.roundingMode()),
                fromJson(version.rulesJson(), FEE_RULE_LIST),
                fromJson(version.metadataJson(), STRING_MAP));
    }

    private Map<String, Object> sanitizedContext(AssessGatewayFeeCommand command) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        Map<String, Object> supplied = command.context() != null ? command.context() : Map.of();
        for (Map.Entry<String, Object> entry : supplied.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Fee context key must not be blank");
            }
            if (FORBIDDEN_CONTEXT_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unsafe fee context key is not allowed: " + key);
            }
            if (!GATEWAY_SCHEMA.contains(key)) {
                throw new IllegalArgumentException("Unknown gateway fee context key: " + key);
            }
            sanitized.put(key, normalizeValue(entry.getValue()));
        }
        sanitized.put("merchantId", command.merchantId().toString());
        sanitized.put("mode", command.mode().name());
        sanitized.put("eventType", command.eventType());
        sanitized.put("eventId", command.eventId().toString());
        putIfPresent(sanitized, "paymentIntentId", command.paymentIntentId());
        putIfPresent(sanitized, "paymentRequestId", command.paymentRequestId());
        putIfPresent(sanitized, "provider", command.provider());
        putIfPresent(sanitized, "connectorAccountId", command.connectorAccountId());
        putIfPresent(sanitized, "paymentMethodType", blankToNull(command.paymentMethodType()));
        putIfPresent(sanitized, "currency", requireCurrency(command.currency()));
        sanitized.put("amountMinor", BigDecimal.valueOf(command.amountMinor()));
        sanitized.put("amount", BigDecimal.valueOf(command.amountMinor(), 2));
        return sanitized;
    }

    private List<GatewayFeeAssessmentLine> toLines(String assessmentId,
                                                  UUID merchantId,
                                                  ApiKeyMode mode,
                                                  List<FeeLine> lines) {
        List<GatewayFeeAssessmentLine> persisted = new ArrayList<>();
        for (FeeLine line : lines) {
            persisted.add(new GatewayFeeAssessmentLine(
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
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof PaymentProvider provider) {
            return provider.name();
        }
        if (value instanceof ApiKeyMode mode) {
            return mode.name();
        }
        return value;
    }

    private static void putIfPresent(Map<String, Object> context, String key, Object value) {
        if (value != null) {
            context.put(key, value instanceof UUID uuid ? uuid.toString() : value.toString());
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static String requireCurrency(String currency) {
        requireText(currency, "currency");
        return currency.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid gateway fee snapshot JSON", ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid gateway fee schedule JSON", ex);
        }
    }
}
