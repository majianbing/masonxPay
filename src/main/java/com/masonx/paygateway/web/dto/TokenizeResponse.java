package com.masonx.paygateway.web.dto;

/**
 * Returned by POST /pub/tokenize.
 * The gateway token is opaque — the frontend passes it to the payment endpoint.
 */
public record TokenizeResponse(String gatewayToken) {}
