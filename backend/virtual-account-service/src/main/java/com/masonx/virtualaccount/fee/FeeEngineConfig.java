package com.masonx.virtualaccount.fee;

import com.masonx.feeengine.DefaultFeeEngine;
import com.masonx.feeengine.FeeEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeeEngineConfig {

    @Bean
    public FeeEngine feeEngine() {
        return DefaultFeeEngine.withAviator();
    }
}
