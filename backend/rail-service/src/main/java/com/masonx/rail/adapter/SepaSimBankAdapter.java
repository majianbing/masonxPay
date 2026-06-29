package com.masonx.rail.adapter;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.rail.iso20022.BankRailHttpClient;
import com.masonx.rail.iso20022.Iso20022LogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ISO 20022 adapter for the SEPA_SIM bank network.
 *
 * <p>Uses the lab bank-simulator at {@code rail.simulator.host}:{@code rail.simulator.http-port}.
 * Production SEPA adapters would target a clearing house endpoint with mTLS.
 */
@Component
public class SepaSimBankAdapter extends AbstractBankIso20022Adapter {

    public SepaSimBankAdapter(BankRailHttpClient httpClient,
                              Iso20022LogService logService,
                              SnowflakeIdGenerator idGen,
                              @Value("${rail.simulator.bank-url:http://localhost:9090}") String bankSimBaseUrl) {
        super(httpClient, logService, idGen, bankSimBaseUrl);
    }

    @Override
    protected String networkName() {
        return "SEPA_SIM";
    }
}
