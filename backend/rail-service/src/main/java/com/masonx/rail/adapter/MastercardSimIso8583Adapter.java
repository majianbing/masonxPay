package com.masonx.rail.adapter;

import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.stereotype.Component;

/**
 * ISO 8583 adapter for the Mastercard simulator rail (network=MC_SIM).
 * Routes 5-prefix PANs.
 */
@Component
public class MastercardSimIso8583Adapter extends AbstractIso8583Adapter {

    public MastercardSimIso8583Adapter(GenericPackager packager,
                                        Iso8583NettyClient client,
                                        Iso8583LogService logService) {
        super(packager, client, logService);
    }

    @Override
    protected String networkName() { return "MC_SIM"; }

    @Override
    protected String acquirerId()  { return "999002"; }
}
