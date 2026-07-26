package com.masonx.virtualaccount.vcc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.domain.constant.CardProgramFundingModel;
import com.masonx.virtualaccount.domain.constant.CardProgramSystemOfRecord;
import com.masonx.virtualaccount.domain.po.CardControlProfile;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

@Component
public class CardAuthorizationControlEvaluator {

    public static final String REASON_CURRENCY_NOT_ALLOWED = "CURRENCY_NOT_ALLOWED";
    public static final String REASON_CARD_SPENDING_LIMIT_EXCEEDED = "CARD_SPENDING_LIMIT_EXCEEDED";
    public static final String REASON_CONTROL_TRANSACTION_LIMIT_EXCEEDED = "CONTROL_TRANSACTION_LIMIT_EXCEEDED";
    public static final String REASON_DAILY_AMOUNT_LIMIT_EXCEEDED = "DAILY_AMOUNT_LIMIT_EXCEEDED";
    public static final String REASON_DAILY_COUNT_LIMIT_EXCEEDED = "DAILY_COUNT_LIMIT_EXCEEDED";
    public static final String REASON_UNSUPPORTED_FUNDING_MODEL = "UNSUPPORTED_FUNDING_MODEL";
    public static final String REASON_INVALID_CONTROL_CONFIGURATION = "INVALID_CONTROL_CONFIGURATION";

    private final ObjectMapper objectMapper;
    private final CardAuthorizationRepository authorizationRepo;
    private final Clock clock;

    @Autowired
    public CardAuthorizationControlEvaluator(ObjectMapper objectMapper,
                                             CardAuthorizationRepository authorizationRepo) {
        this(objectMapper, authorizationRepo, Clock.systemUTC());
    }

    CardAuthorizationControlEvaluator(ObjectMapper objectMapper,
                                      CardAuthorizationRepository authorizationRepo,
                                      Clock clock) {
        this.objectMapper = objectMapper;
        this.authorizationRepo = authorizationRepo;
        this.clock = clock;
    }

    public CardControlEvaluation evaluate(VirtualCard card,
                                          CardProgram program,
                                          Optional<CardControlProfile> cardControlProfile,
                                          IssuerAuthRequest request) {
        if (program != null && !usesInternalPrefundedDecisioning(program)) {
            return CardControlEvaluation.declined(REASON_UNSUPPORTED_FUNDING_MODEL);
        }
        if (!card.currency().equalsIgnoreCase(request.currency())) {
            return CardControlEvaluation.declined(REASON_CURRENCY_NOT_ALLOWED);
        }
        if (card.spendingLimit() != null && request.amount().compareTo(card.spendingLimit()) > 0) {
            return CardControlEvaluation.declined(REASON_CARD_SPENDING_LIMIT_EXCEEDED);
        }

        CardControlEvaluation programDecision = evaluateJson(card, program != null ? program.defaultControlsJson() : null,
                request);
        if (!programDecision.approved()) {
            return programDecision;
        }
        return evaluateJson(card, cardControlProfile.map(CardControlProfile::controlsJson).orElse(null), request);
    }

    private boolean usesInternalPrefundedDecisioning(CardProgram program) {
        return program.systemOfRecord() == CardProgramSystemOfRecord.INTERNAL
                && (program.fundingModel() == CardProgramFundingModel.PREFUNDED_CARD_BALANCE
                || program.fundingModel() == CardProgramFundingModel.SIMULATED);
    }

    private CardControlEvaluation evaluateJson(VirtualCard card, String controlsJson, IssuerAuthRequest request) {
        JsonNode controls = parseControls(controlsJson);
        if (controls == null || controls.isEmpty()) {
            return CardControlEvaluation.allow();
        }
        if (controls.path("_invalid").asBoolean(false)) {
            return CardControlEvaluation.declined(REASON_INVALID_CONTROL_CONFIGURATION);
        }

        String requestCurrency = request.currency().toUpperCase(Locale.ROOT);
        if (matchesCurrencyList(controls.path("blockedCurrencies"), requestCurrency)) {
            return CardControlEvaluation.declined(REASON_CURRENCY_NOT_ALLOWED);
        }
        JsonNode allowedCurrencies = controls.path("allowedCurrencies");
        if (allowedCurrencies.isArray() && !matchesCurrencyList(allowedCurrencies, requestCurrency)) {
            return CardControlEvaluation.declined(REASON_CURRENCY_NOT_ALLOWED);
        }

        Optional<BigDecimal> maxTransactionAmount = decimalControl(controls, "maxTransactionAmount");
        if (maxTransactionAmount.isPresent() && request.amount().compareTo(maxTransactionAmount.get()) > 0) {
            return CardControlEvaluation.declined(REASON_CONTROL_TRANSACTION_LIMIT_EXCEEDED);
        }

        Optional<BigDecimal> dailyAmountLimit = decimalControl(controls, "dailyAmountLimit");
        Optional<Long> dailyCountLimit = longControl(controls, "dailyCountLimit");
        if (dailyAmountLimit.isPresent() || dailyCountLimit.isPresent()) {
            Instant todayStart = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
            var velocity = authorizationRepo.authorizedVelocitySince(card.cardId(), request.currency(), todayStart);
            if (dailyAmountLimit.isPresent()
                    && velocity.amount().add(request.amount()).compareTo(dailyAmountLimit.get()) > 0) {
                return CardControlEvaluation.declined(REASON_DAILY_AMOUNT_LIMIT_EXCEEDED);
            }
            if (dailyCountLimit.isPresent() && velocity.count() + 1 > dailyCountLimit.get()) {
                return CardControlEvaluation.declined(REASON_DAILY_COUNT_LIMIT_EXCEEDED);
            }
        }

        return CardControlEvaluation.allow();
    }

    private JsonNode parseControls(String controlsJson) {
        if (controlsJson == null || controlsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(controlsJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("_invalid", true);
        }
    }

    private Optional<BigDecimal> decimalControl(JsonNode controls, String fieldName) {
        JsonNode value = controls.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value.asText()));
        } catch (NumberFormatException e) {
            return Optional.of(BigDecimal.valueOf(-1));
        }
    }

    private Optional<Long> longControl(JsonNode controls, String fieldName) {
        JsonNode value = controls.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        if (!value.canConvertToLong()) {
            return Optional.of(-1L);
        }
        return Optional.of(value.asLong());
    }

    private boolean matchesCurrencyList(JsonNode currencies, String requestCurrency) {
        if (!currencies.isArray()) {
            return false;
        }
        for (JsonNode currency : currencies) {
            if (requestCurrency.equals(currency.asText().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
