package com.masonx.rail.adapter;

import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.stereotype.Component;

/**
 * ISO 8583 adapter for the Visa simulator rail (network=VISA_SIM).
 *
 * <p>Routes 4-prefix PANs and VA-issued BIN 999999 cards. The rail-simulator's
 * {@code CardNetworkSimHandler} internally routes BIN 999999 to the VA issuer;
 * from rail-service's perspective, all such PANs go through VISA_SIM.
 */
@Component
public class VisaSimIso8583Adapter extends AbstractIso8583Adapter {

    public VisaSimIso8583Adapter(GenericPackager packager,
                                  Iso8583NettyClient client,
                                  Iso8583LogService logService) {
        super(packager, client, logService);
    }

    @Override
    protected String networkName() { return "VISA_SIM"; }

    @Override
    protected String acquirerId()  { return "999001"; }
}
