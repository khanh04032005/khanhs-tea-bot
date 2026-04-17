package com.khanh.teashop.khanhs_tea_bot.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {
    private Long id;
    private String customerName;
    private String customerPhone;
    private String status;
    private Integer totalAmount;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;
}