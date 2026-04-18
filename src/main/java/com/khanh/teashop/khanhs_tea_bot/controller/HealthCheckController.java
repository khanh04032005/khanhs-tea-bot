package com.khanh.teashop.khanhs_tea_bot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/")
    public String healthCheck() {
        // Trả về một chuỗi bất kỳ để Cronjob nhận được HTTP 200 OK
        return "TeaShop Bot is Online!";
    }
}