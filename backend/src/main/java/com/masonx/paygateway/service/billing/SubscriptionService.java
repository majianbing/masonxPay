package com.masonx.paygateway.service.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.web.dto.CreateSubscriptionCheckoutLinkRequest;
import com.masonx.paygateway.web.dto.CreateSubscriptionRequest;
import com.masonx.paygateway.web.dto.SubscriptionCheckoutLinkResponse;
import com.masonx.paygateway.web.dto.SubscriptionItemResponse;
import com.masonx.paygateway.web.dto.SubscriptionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

    @Value("${app.pay-base-url:http://localhost:3000}")
    private String payBaseUrl;

    private final BillingCustomerRepository customerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private final ObjectMapper objectMapper;

    public SubscriptionService(BillingCustomerRepository customerRepository,
                               SubscriptionRepository subscriptionRepository,
                               SubscriptionItemRepository itemRepository,
                               SubscriptionCheckoutLinkRepository checkoutLinkRepository,
                               ObjectMapper objectMapper) {
        this.customerRepository = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.checkoutLinkRepository = checkoutLinkRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionResponse> list(UUID merchantId, ApiKeyMode mode, UUID customerId, Pageable pageable) {
        Page<Subscription> page = customerId == null
                ? subscriptionRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode, pageable)
                : subscriptionRepository.findByMerchantIdAndModeAndCustomerIdOrderByCreatedAtDesc(merchantId, mode, customerId, pageable);
        return page.map(this::response);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse get(UUID merchantId, UUID subscriptionId) {
        return response(loadOwnedSubscription(merchantId, subscriptionId));
    }

    @Transactional
    public SubscriptionResponse create(UUID merchantId, ApiKeyMode mode, CreateSubscriptionRequest request) {
        customerRepository.findByIdAndMerchantIdAndMode(request.customerId(), merchantId, mode)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        if (request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        if (request.intervalUnit() == null) {
            throw new IllegalArgumentException("Interval unit is required");
        }
        if (request.intervalCount() < 1) {
            throw new IllegalArgumentException("Interval count must be positive");
        }
        validateItems(request.items());

        Instant now = Instant.now();
        Subscription subscription = new Subscription();
        subscription.setMerchantId(merchantId);
        subscription.setCustomerId(request.customerId());
        subscription.setMode(mode);
        subscription.setCurrency(request.currency().trim().toLowerCase());
        subscription.setIntervalUnit(request.intervalUnit());
        subscription.setIntervalCount(request.intervalCount());
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(periodEnd(now, request.intervalUnit(), request.intervalCount()));
        subscription.setMetadataJson(serializeMetadata(request.metadata()));

        int trialDays = request.trialDays() == null ? 0 : request.trialDays();
        if (trialDays > 0) {
            subscription.setStatus(SubscriptionStatus.TRIALING);
            subscription.setTrialEndsAt(now.plus(trialDays, ChronoUnit.DAYS));
        } else {
            subscription.setStatus(SubscriptionStatus.INCOMPLETE);
        }

        Subscription saved = subscriptionRepository.save(subscription);
        List<SubscriptionItem> items = request.items().stream()
                .map(itemRequest -> item(saved, itemRequest))
                .toList();
        itemRepository.saveAll(items);
        return response(saved);
    }

    @Transactional
    public SubscriptionCheckoutLinkResponse createCheckoutLink(UUID merchantId,
                                                               UUID subscriptionId,
                                                               CreateSubscriptionCheckoutLinkRequest request) {
        Subscription subscription = loadOwnedSubscription(merchantId, subscriptionId);
        if (subscription.getStatus() == SubscriptionStatus.CANCELED || subscription.getStatus() == SubscriptionStatus.UNPAID) {
            throw new IllegalStateException("Checkout link cannot be created for this subscription status");
        }

        SubscriptionCheckoutLink link = new SubscriptionCheckoutLink();
        link.setMerchantId(merchantId);
        link.setCustomerId(subscription.getCustomerId());
        link.setSubscriptionId(subscription.getId());
        link.setToken("sub_" + UUID.randomUUID().toString().replace("-", ""));
        link.setStatus(SubscriptionCheckoutLinkStatus.ACTIVE);
        Instant expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Checkout link expiration must be in the future");
        }
        link.setExpiresAt(expiresAt);
        return SubscriptionCheckoutLinkResponse.from(checkoutLinkRepository.save(link), payBaseUrl);
    }

    @Transactional
    public SubscriptionResponse cancel(UUID merchantId, UUID subscriptionId) {
        Subscription subscription = loadOwnedSubscription(merchantId, subscriptionId);
        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            throw new IllegalStateException("Subscription is already canceled");
        }
        if (subscription.getStatus() == SubscriptionStatus.UNPAID) {
            throw new IllegalStateException("Unpaid subscriptions cannot be canceled this way");
        }
        subscription.setStatus(SubscriptionStatus.CANCELED);
        subscription.setCanceledAt(java.time.Instant.now());
        return response(subscriptionRepository.save(subscription));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionCheckoutLinkResponse> listCheckoutLinks(UUID merchantId, UUID subscriptionId) {
        loadOwnedSubscription(merchantId, subscriptionId);
        return checkoutLinkRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(merchantId, subscriptionId)
                .stream()
                .map(link -> SubscriptionCheckoutLinkResponse.from(link, payBaseUrl))
                .toList();
    }

    private Subscription loadOwnedSubscription(UUID merchantId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
    }

    private SubscriptionItem item(Subscription subscription, CreateSubscriptionRequest.SubscriptionItemRequest request) {
        SubscriptionItem item = new SubscriptionItem();
        item.setMerchantId(subscription.getMerchantId());
        item.setSubscriptionId(subscription.getId());
        item.setDescription(request.description().trim());
        item.setAmount(request.amount());
        item.setQuantity(request.quantity());
        return item;
    }

    private void validateItems(List<CreateSubscriptionRequest.SubscriptionItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one subscription item is required");
        }
        items.forEach(item -> {
            if (item.amount() < 50) {
                throw new IllegalArgumentException("Subscription item amount must be at least 50");
            }
            if (item.quantity() < 1) {
                throw new IllegalArgumentException("Subscription item quantity must be positive");
            }
        });
    }

    private Instant periodEnd(Instant start, com.masonx.paygateway.domain.billing.BillingIntervalUnit unit, int count) {
        ZonedDateTime utcStart = start.atZone(ZoneOffset.UTC);
        ZonedDateTime utcEnd = switch (unit) {
            case DAY -> utcStart.plusDays(count);
            case WEEK -> utcStart.plusWeeks(count);
            case MONTH -> utcStart.plusMonths(count);
            case YEAR -> utcStart.plusYears(count);
        };
        return utcEnd.toInstant();
    }

    private SubscriptionResponse response(Subscription subscription) {
        List<SubscriptionItemResponse> items = itemRepository
                .findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(subscription.getMerchantId(), subscription.getId())
                .stream()
                .map(SubscriptionItemResponse::from)
                .toList();
        return SubscriptionResponse.from(subscription, items, parseMetadata(subscription.getMetadataJson()));
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Subscription metadata is not serializable");
        }
    }

    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
