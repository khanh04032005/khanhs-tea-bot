package com.khanh.teashop.khanhs_tea_bot.dto;

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
public class OrderItemResponse {
    private Long id;
    private String productId;
    private String productName;
    private String size;
    private Integer quantity;
    private Integer unitPrice;
    private Integer lineTotal;
}