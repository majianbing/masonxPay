package com.masonx.rail.adapter;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.rail.iso20022.BankRailHttpClient;
import com.masonx.rail.iso20022.Iso20022LogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ISO 20022 adapter for the FEDNOW_SIM bank network.
 *
 * <p>Same simulator as SEPA_SIM for the lab; network name distinguishes routing.
 * Production FedNow adapters would use the Fed's ISO 20022 API with credential-based auth.
 */
@Component
public class FedNowSimBankAdapter extends AbstractBankIso20022Adapter {

    public FedNowSimBankAdapter(BankRailHttpClient httpClient,
                                Iso20022LogService logService,
                                SnowflakeIdGenerator idGen,
                                @Value("${rail.simulator.bank-url:http://localhost:9090}") String bankSimBaseUrl) {
        super(httpClient, logService, idGen, bankSimBaseUrl);
    }

    @Override
    protected String networkName() {
        return "FEDNOW_SIM";
    }
}
