package com.khanh.teashop.khanhs_tea_bot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class CreateOrderItemRequest {

    @NotBlank(message = "productId is required")
    private String productId;

    @NotBlank(message = "size is required")
    private String size;

    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;
}