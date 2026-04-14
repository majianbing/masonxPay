package com.masonx.paygateway.domain.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class BillingDetailsConverter implements AttributeConverter<BillingDetails, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(BillingDetails attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize BillingDetails", e);
        }
    }

    @Override
    public BillingDetails convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, BillingDetails.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize BillingDetails", e);
        }
    }
}
