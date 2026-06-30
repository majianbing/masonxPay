package com.masonx.rail.iso8583;

import org.jpos.iso.packager.GenericPackager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Iso8583Config {

    @Bean
    public GenericPackager iso8583Packager() {
        try {
            return new GenericPackager(
                    getClass().getClassLoader().getResourceAsStream("iso8583-packager.xml"));
        } catch (org.jpos.iso.ISOException e) {
            throw new IllegalStateException("Failed to load iso8583-packager.xml", e);
        }
    }
}
