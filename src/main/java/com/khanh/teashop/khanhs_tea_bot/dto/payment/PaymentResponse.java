package com.khanh.teashop.khanhs_tea_bot.dto.payment;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class PaymentResponse {
    private Long paymentId;
    private Long orderId;
    private Long providerOrderCode;
    private Integer amount;
    private String status;
    private String checkoutUrl;
    private String qrCode;
}