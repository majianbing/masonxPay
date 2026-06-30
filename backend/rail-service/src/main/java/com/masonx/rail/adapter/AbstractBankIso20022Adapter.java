package com.masonx.rail.adapter;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.PaymentRailAdapter;
import com.masonx.rail.canonical.RailPaymentStatus;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.rail.iso20022.BankRailHttpClient;
import com.masonx.rail.iso20022.Iso20022LogService;
import com.masonx.rail.iso20022.Iso20022MessageType;
import com.masonx.rail.iso20022.Iso20022ParsedMessage;
import com.masonx.rail.iso20022.Iso20022Parser;
import com.masonx.rail.iso20022.Pain001Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Shared ISO 20022 adapter logic for bank-rail simulators (SEPA, FedNow).
 *
 * <p>Flow per {@link #execute}:
 * <ol>
 *   <li>Generate message IDs (messageId, instructionId, endToEndId).
 *   <li>Build pain.001 XML and POST to the bank-sim.
 *   <li>Parse the synchronous pain.002 response.
 *   <li>Persist correlation IDs to {@code rail_network_correlation}.
 *   <li>Return ACCEPTED (ACCP) or DECLINED (RJCT) canonical response immediately.
 * </ol>
 * Subsequent async messages (pacs.002 settlement, pacs.004 return, camt.054 notification)
 * are handled by {@link com.masonx.rail.service.BankPaymentPoller}.
 */
public abstract class AbstractBankIso20022Adapter implements PaymentRailAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractBankIso20022Adapter.class);

    protected final BankRailHttpClient  httpClient;
    protected final Iso20022LogService  logService;
    protected final SnowflakeIdGenerator idGen;
    private   final String              bankSimBaseUrl;

    protected AbstractBankIso20022Adapter(BankRailHttpClient httpClient,
                                          Iso20022LogService logService,
                                          SnowflakeIdGenerator idGen,
                                          String bankSimBaseUrl) {
        this.httpClient       = httpClient;
        this.logService       = logService;
        this.idGen            = idGen;
        this.bankSimBaseUrl   = bankSimBaseUrl;
    }

    /** Network identifier matched against the {@code network} metadata key. */
    protected abstract String networkName();

    @Override
    public boolean supports(PaymentRail rail, String network) {
        return PaymentRail.BANK_ISO20022.equals(rail) && networkName().equals(network);
    }

    @Override
    public RailResponse execute(CanonicalPaymentCommand command) {
        String messageId     = idGen.generate("m20_");
        String instructionId = idGen.generate("ins_");
        String endToEndId    = idGen.generate("e2e_");

        String pain001Xml = Pain001Builder.build(command, messageId, instructionId, endToEndId);
        logService.logSend(command.paymentId(), networkName(),
                Iso20022MessageType.PAIN_001, messageId, instructionId, endToEndId);

        String pain002Xml;
        try {
            pain002Xml = httpClient.sendPain001(pain001Xml, bankSimBaseUrl);
        } catch (Exception e) {
            log.error("Bank-sim connection failure paymentId={}: {}", command.paymentId(), e.getMessage(), e);
            return new RailResponse(command.paymentId(), RailPaymentStatus.FAILED,
                    null, null, endToEndId, "Bank-sim unreachable: " + e.getMessage(), Instant.now());
        }

        Iso20022ParsedMessage pain002;
        try {
            pain002 = Iso20022Parser.parse(pain002Xml);
        } catch (Exception e) {
            log.error("Failed to parse pain.002 paymentId={}: {}", command.paymentId(), e.getMessage(), e);
            return new RailResponse(command.paymentId(), RailPaymentStatus.FAILED,
                    null, null, endToEndId, "Invalid pain.002 response: " + e.getMessage(), Instant.now());
        }

        logService.logReceive(command.paymentId(), networkName(), pain002);
        logService.persistCorrelation(command.paymentId(), networkName(), messageId, endToEndId);

        log.info("Bank transfer pain.002 paymentId={} status={} e2eId={}",
                command.paymentId(), pain002.statusCode(), endToEndId);

        if (pain002.isRejected()) {
            return new RailResponse(command.paymentId(), RailPaymentStatus.DECLINED,
                    null, pain002.statusCode(), endToEndId,
                    "Rejected by bank-sim: " + pain002.reasonCode(), Instant.now());
        }

        if (pain002.isAccepted()) {
            return new RailResponse(command.paymentId(), RailPaymentStatus.ACCEPTED,
                    null, pain002.statusCode(), endToEndId, null, Instant.now());
        }

        return new RailResponse(command.paymentId(), RailPaymentStatus.FAILED,
                null, pain002.statusCode(), endToEndId,
                "Unexpected pain.002 status: " + pain002.statusCode(), Instant.now());
    }

    @Override
    public RailResponse query(String railPaymentId) {
        throw new UnsupportedOperationException("Bank rail query not implemented — use BankPaymentPoller");
    }

    @Override
    public RailResponse reverse(String railPaymentId, String reasonCode) {
        throw new UnsupportedOperationException("Bank rail return (pacs.004) not yet implemented");
    }
}
