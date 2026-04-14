package com.masonx.paygateway.domain.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ShippingDetailsConverter implements AttributeConverter<ShippingDetails, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ShippingDetails attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize ShippingDetails", e);
        }
    }

    @Override
    public ShippingDetails convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, ShippingDetails.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize ShippingDetails", e);
        }
    }
}
