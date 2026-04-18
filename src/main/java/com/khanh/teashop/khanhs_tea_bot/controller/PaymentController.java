package com.khanh.teashop.khanhs_tea_bot.controller;

import com.khanh.teashop.khanhs_tea_bot.dto.payment.PaymentResponse;
import com.khanh.teashop.khanhs_tea_bot.service.PayOsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PayOsService payOsService;

    @PostMapping("/orders/{orderId}/create")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@PathVariable Long orderId) {
        return payOsService.createPaymentForOrder(orderId);
    }

    @GetMapping("/orders/{orderId}")
    public PaymentResponse get(@PathVariable Long orderId) {
        return payOsService.getByOrderId(orderId);
    }

    @PostMapping("/webhook/payos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(@RequestBody Map<String, Object> payload) {
        payOsService.handleWebhook(payload);
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }
}