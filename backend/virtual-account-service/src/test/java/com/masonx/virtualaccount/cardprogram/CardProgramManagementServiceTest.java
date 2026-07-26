package com.masonx.virtualaccount.cardprogram;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.cardprogram.dto.CreateCardProgramRequest;
import com.masonx.virtualaccount.cardprogram.dto.CreateCardholderRequest;
import com.masonx.virtualaccount.cardprogram.dto.CreateIssuerPartnerRequest;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardholderRepository;
import com.masonx.virtualaccount.domain.IssuerPartnerRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.Cardholder;
import com.masonx.virtualaccount.domain.po.IssuerPartner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardProgramManagementServiceTest {

    @Mock IssuerPartnerRepository issuerPartners;
    @Mock CardProgramRepository cardPrograms;
    @Mock CardholderRepository cardholders;
    @Mock SnowflakeIdGenerator idGen;

    @Test
    void createIssuerPartner_defaults_to_test_draft_and_saves_tenant_scoped_partner() {
        var service = service();
        when(idGen.generate(MasonXIdPrefix.ISSUER_PARTNER.prefix())).thenReturn("ip_1");

        var response = service.createIssuerPartner(new CreateIssuerPartnerRequest(
                "mer_1", null, "Rail Sim", IssuerPartnerType.RAIL_SIM, null,
                "cred_ref", null, "whsec_ref", "ext_prog", "ext_funding"));

        assertThat(response.issuerPartnerId()).isEqualTo("ip_1");
        assertThat(response.mode()).isEqualTo(Mode.TEST);
        assertThat(response.status()).isEqualTo(IssuerPartnerStatus.DRAFT);
        var captor = ArgumentCaptor.forClass(IssuerPartner.class);
        verify(issuerPartners).save(captor.capture());
        assertThat(captor.getValue().merchantId()).isEqualTo("mer_1");
        assertThat(captor.getValue().configJson()).isEqualTo("{}");
    }

    @Test
    void createCardProgram_requires_same_tenant_mode_issuer_partner() {
        var service = service();
        when(issuerPartners.findByIdForMerchant("ip_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCardProgram(cardProgramRequest(CardProgramStatus.DRAFT)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createCardProgram_rejects_active_program_when_issuer_partner_not_active() {
        var service = service();
        when(issuerPartners.findByIdForMerchant("ip_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.DRAFT)));

        assertThatThrownBy(() -> service.createCardProgram(cardProgramRequest(CardProgramStatus.ACTIVE)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createCardProgram_saves_program_for_active_issuer_partner() {
        var service = service();
        when(issuerPartners.findByIdForMerchant("ip_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(issuerPartner(IssuerPartnerStatus.ACTIVE)));
        when(idGen.generate(MasonXIdPrefix.CARD_PROGRAM.prefix())).thenReturn("cprog_1");

        var response = service.createCardProgram(cardProgramRequest(CardProgramStatus.ACTIVE));

        assertThat(response.programId()).isEqualTo("cprog_1");
        assertThat(response.status()).isEqualTo(CardProgramStatus.ACTIVE);
        var captor = ArgumentCaptor.forClass(CardProgram.class);
        verify(cardPrograms).save(captor.capture());
        assertThat(captor.getValue().merchantId()).isEqualTo("mer_1");
        assertThat(captor.getValue().issuerPartnerId()).isEqualTo("ip_1");
        assertThat(captor.getValue().currency()).isEqualTo("USD");
    }

    @Test
    void createCardholder_defaults_to_test_pending_and_saves_minimal_reference() {
        var service = service();
        when(idGen.generate(MasonXIdPrefix.CARDHOLDER.prefix())).thenReturn("ch_1");

        var response = service.createCardholder(new CreateCardholderRequest(
                "mer_1", null, CardholderType.EMPLOYEE, "ext_ch_1",
                null, "Jane", "employee-42", "profile_ref"));

        assertThat(response.cardholderId()).isEqualTo("ch_1");
        assertThat(response.mode()).isEqualTo(Mode.TEST);
        assertThat(response.kycStatus()).isEqualTo(CardholderKycStatus.PENDING);
        var captor = ArgumentCaptor.forClass(Cardholder.class);
        verify(cardholders).save(captor.capture());
        assertThat(captor.getValue().externalIssuerCardholderId()).isEqualTo("ext_ch_1");
    }

    private CardProgramManagementService service() {
        return new CardProgramManagementService(issuerPartners, cardPrograms, cardholders, idGen);
    }

    private static CreateCardProgramRequest cardProgramRequest(CardProgramStatus status) {
        return new CreateCardProgramRequest(
                "mer_1",
                Mode.TEST,
                "ip_1",
                "Expense Cards",
                "usd",
                "{\"bin\":\"999999\"}",
                status,
                CardProgramSystemOfRecord.INTERNAL,
                CardProgramFundingModel.SIMULATED,
                null,
                null,
                null,
                null,
                "ext_prog",
                "ext_funding");
    }

    private static IssuerPartner issuerPartner(IssuerPartnerStatus status) {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        return new IssuerPartner(
                "ip_1",
                "mer_1",
                Mode.TEST,
                "Rail Sim",
                IssuerPartnerType.RAIL_SIM,
                status,
                null,
                "{}",
                null,
                "ext_prog",
                "ext_funding",
                now,
                now);
    }
}
