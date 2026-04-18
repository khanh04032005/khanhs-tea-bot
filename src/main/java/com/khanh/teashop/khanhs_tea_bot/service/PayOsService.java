package com.khanh.teashop.khanhs_tea_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khanh.teashop.khanhs_tea_bot.config.PayOsProperties;
import com.khanh.teashop.khanhs_tea_bot.dto.payment.PaymentResponse;
import com.khanh.teashop.khanhs_tea_bot.entity.Order;
import com.khanh.teashop.khanhs_tea_bot.entity.OrderStatus;
import com.khanh.teashop.khanhs_tea_bot.entity.Payment;
import com.khanh.teashop.khanhs_tea_bot.entity.PaymentStatus;
import com.khanh.teashop.khanhs_tea_bot.repository.OrderRepository;
import com.khanh.teashop.khanhs_tea_bot.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PayOsService {

    private final PayOsProperties payOsProperties;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TelegramNotifyService telegramNotifyService;
    private final OrderFulfillmentNotifyService orderFulfillmentNotifyService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public PaymentResponse createPaymentForOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (order.getStatus() == OrderStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order already paid");
        }

        Payment existing = paymentRepository.findByOrderId(orderId).orElse(null);
        if (existing != null && existing.getStatus() == PaymentStatus.PENDING) {
            return toResponse(existing);
        }

        long providerOrderCode = System.currentTimeMillis();

        Map<String, Object> body = new HashMap<>();
        body.put("orderCode", providerOrderCode);
        body.put("amount", order.getTotalAmount());
        body.put("description", buildSafeDescription(order.getId()));
        body.put("returnUrl", payOsProperties.getReturnUrl());
        body.put("cancelUrl", payOsProperties.getCancelUrl());

        List<Map<String, Object>> items = order.getItems().stream().map(item -> {
            Map<String, Object> i = new HashMap<>();
            i.put("name", safeItemName(item.getProduct().getName()));
            i.put("quantity", item.getQuantity());
            i.put("price", item.getUnitPrice());
            return i;
        }).toList();
        body.put("items", items);

        body.put("signature", createSignature(body, payOsProperties.getChecksumKey()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", payOsProperties.getClientId());
        headers.set("x-api-key", payOsProperties.getApiKey());

        String url = payOsProperties.getBaseUrl() + "/v2/payment-requests";

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            String raw = resp.getBody();
            if (raw == null || raw.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payOS empty response");
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("data");

            String checkoutUrl = data.path("checkoutUrl").asText("");
            String qrCode = data.path("qrCode").asText("");

            if (checkoutUrl.isBlank()) {
                String code = root.path("code").asText("");
                String desc = root.path("desc").asText("");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payOS error: code=" + code + ", desc=" + desc);
            }

            Payment payment = existing != null ? existing : new Payment();
            payment.setOrder(order);
            payment.setProviderOrderCode(providerOrderCode);
            payment.setAmount(order.getTotalAmount());
            payment.setCheckoutUrl(checkoutUrl);
            payment.setQrCode(qrCode);
            payment.setStatus(PaymentStatus.PENDING);

            return toResponse(paymentRepository.save(payment));
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "payOS http " + ex.getRawStatusCode() + ": " + ex.getResponseBodyAsString()
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payOS parse failed: " + ex.getMessage());
        }
    }

    @Transactional
    public void handleWebhook(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }

        Object dataObj = payload.get("data");
        if (!(dataObj instanceof Map<?, ?> rawData)) {
            return;
        }

        Object orderCodeObj = rawData.get("orderCode");
        if (orderCodeObj == null) {
            return;
        }

        Long providerOrderCode;
        try {
            providerOrderCode = Long.valueOf(orderCodeObj.toString());
        } catch (NumberFormatException ex) {
            return;
        }

        Payment payment = paymentRepository.findByProviderOrderCode(providerOrderCode).orElse(null);
        if (payment == null) {
            return;
        }

        String code = payload.get("code") == null ? "" : payload.get("code").toString();

        if ("00".equals(code)) {
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(LocalDateTime.now());

            Order paidOrder = payment.getOrder();
            paidOrder.setStatus(OrderStatus.PAID);

            paymentRepository.save(payment);
            orderRepository.save(paidOrder);

            orderFulfillmentNotifyService.notifyFulfillment(paidOrder, payment);

            telegramNotifyService.send(
                    paidOrder.getTelegramChatId(),
                    "Thanh toán thành công ✅\nMã đơn: " + paidOrder.getId()
                            + "\nTổng tiền: " + paidOrder.getTotalAmount() + " VND"
            );
            return;
        }

        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(Long orderId) {
        Payment p = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return toResponse(p);
    }

    private PaymentResponse toResponse(Payment p) {
        return PaymentResponse.builder()
                .paymentId(p.getId())
                .orderId(p.getOrder().getId())
                .providerOrderCode(p.getProviderOrderCode())
                .amount(p.getAmount())
                .status(p.getStatus().name())
                .checkoutUrl(p.getCheckoutUrl())
                .qrCode(p.getQrCode())
                .build();
    }

    private String buildSafeDescription(Long orderId) {
        return "DH" + orderId;
    }

    private String safeItemName(String name) {
        if (name == null || name.isBlank()) {
            return "ITEM";
        }
        String s = name.replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.length() > 25 ? s.substring(0, 25) : s;
    }

    private String createSignature(Map<String, Object> body, String checksumKey) {
        String raw = "amount=" + body.get("amount")
                + "&cancelUrl=" + body.get("cancelUrl")
                + "&description=" + body.get("description")
                + "&orderCode=" + body.get("orderCode")
                + "&returnUrl=" + body.get("returnUrl");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Cannot create payOS signature", ex);
        }
    }
}