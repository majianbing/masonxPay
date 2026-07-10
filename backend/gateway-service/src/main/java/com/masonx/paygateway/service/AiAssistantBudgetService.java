package com.masonx.paygateway.service;

import com.masonx.paygateway.config.AiAssistantProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AiAssistantBudgetService {

    private final AiAssistantProperties properties;
    private final Clock clock;
    private final ConcurrentMap<UUID, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public AiAssistantBudgetService(AiAssistantProperties properties) {
        this(properties, Clock.systemUTC());
    }

    // Test-only constructor for injecting a fixed Clock; @Autowired above marks the Spring one.
    AiAssistantBudgetService(AiAssistantProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public BudgetResult reserve(UUID merchantId, String question) {
        int estimatedTokens = estimateTokens(question);
        WindowCounter counter = counters.computeIfAbsent(merchantId, ignored -> new WindowCounter(now()));
        synchronized (counter) {
            Instant now = now();
            if (!now.isBefore(counter.windowStartedAt.plusSeconds(properties.budgetWindowSeconds()))) {
                counter.windowStartedAt = now;
                counter.requestCount = 0;
                counter.tokenCount = 0;
            }
            if (counter.requestCount + 1 > properties.requestLimitPerWindow()) {
                return BudgetResult.rejected("request_budget_exceeded", estimatedTokens,
                        properties.requestLimitPerWindow() - counter.requestCount,
                        properties.tokenLimitPerWindow() - counter.tokenCount);
            }
            if (counter.tokenCount + estimatedTokens > properties.tokenLimitPerWindow()) {
                return BudgetResult.rejected("token_budget_exceeded", estimatedTokens,
                        properties.requestLimitPerWindow() - counter.requestCount,
                        properties.tokenLimitPerWindow() - counter.tokenCount);
            }
            counter.requestCount++;
            counter.tokenCount += estimatedTokens;
            return BudgetResult.accepted(estimatedTokens,
                    properties.requestLimitPerWindow() - counter.requestCount,
                    properties.tokenLimitPerWindow() - counter.tokenCount);
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private int estimateTokens(String question) {
        if (question == null || question.isBlank()) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(question.length() / 4.0));
    }

    public record BudgetResult(
            boolean accepted,
            String rejectionReason,
            int estimatedTokens,
            int requestsRemaining,
            int tokensRemaining
    ) {
        static BudgetResult accepted(int estimatedTokens, int requestsRemaining, int tokensRemaining) {
            return new BudgetResult(true, null, estimatedTokens, requestsRemaining, tokensRemaining);
        }

        static BudgetResult rejected(String reason, int estimatedTokens, int requestsRemaining, int tokensRemaining) {
            return new BudgetResult(false, reason, estimatedTokens, Math.max(0, requestsRemaining), Math.max(0, tokensRemaining));
        }
    }

    private static class WindowCounter {
        private Instant windowStartedAt;
        private int requestCount;
        private int tokenCount;

        private WindowCounter(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
