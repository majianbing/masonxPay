package com.masonx.virtualaccount.vcc;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardAuthorizationQueryServiceTest {

    @Mock CardAuthorizationRepository authorizationRepo;

    @Test
    void list_maps_authorizations_and_page_metadata() {
        CardAuthorizationQueryService service = new CardAuthorizationQueryService(authorizationRepo);
        when(authorizationRepo.findByMerchant("mer_1", "TEST", 0, 20))
                .thenReturn(List.of(auth()));
        when(authorizationRepo.countByMerchant("mer_1", "TEST")).thenReturn(21L);

        var result = service.list("mer_1", Mode.TEST, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).decision()).isEqualTo("APPROVED");
        assertThat(result.content().get(0).status()).isEqualTo("AUTHORIZED");
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    private static CardAuthorization auth() {
        return new CardAuthorization(
                "cauth_1",
                "RAIL_SIM",
                "auth_1",
                "card_1",
                "123456",
                "rrn_1",
                new BigDecimal("25.00"),
                "USD",
                "APPROVED",
                null,
                "hold_1",
                CardAuthorizationStatus.AUTHORIZED,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                null,
                Instant.parse("2026-07-26T08:00:00Z"));
    }
}
