package com.khanh.teashop.khanhs_tea_bot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "provider_order_code", nullable = false, unique = true)
    private Long providerOrderCode;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "checkout_url", nullable = false, length = 1000)
    private String checkoutUrl;

    @Column(name = "qr_code", length = 4000)
    private String qrCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @PrePersist
    public void prePersist() {
        if (status == null) status = PaymentStatus.PENDING;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}