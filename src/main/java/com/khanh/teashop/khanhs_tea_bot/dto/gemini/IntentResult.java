package com.khanh.teashop.khanhs_tea_bot.dto.gemini;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IntentResult {
    private String intent;
    private String productId;
    private String size;
    private String address;
    private Integer quantity;
    private String customerName;
    private String customerPhone;
}