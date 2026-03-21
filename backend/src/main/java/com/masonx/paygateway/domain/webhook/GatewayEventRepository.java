package com.masonx.paygateway.domain.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GatewayEventRepository extends JpaRepository<GatewayEvent, UUID> {
}
