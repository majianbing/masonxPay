package com.masonx.paygateway.fee;

import com.masonx.feeengine.DefaultFeeEngine;
import com.masonx.feeengine.FeeEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayFeeEngineConfig {

    @Bean
    public FeeEngine gatewayFeeEngine() {
        return DefaultFeeEngine.withAviator();
    }
}
