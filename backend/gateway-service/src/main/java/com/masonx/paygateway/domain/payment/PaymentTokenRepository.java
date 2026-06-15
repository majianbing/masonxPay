package com.masonx.paygateway.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentTokenRepository extends JpaRepository<PaymentToken, UUID> {}
