package com.khanh.teashop.khanhs_tea_bot.repository;

import com.khanh.teashop.khanhs_tea_bot.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByProviderOrderCode(Long providerOrderCode);
}