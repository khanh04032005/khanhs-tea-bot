package com.khanh.teashop.khanhs_tea_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khanh.teashop.khanhs_tea_bot.dto.gemini.IntentResult;
import com.khanh.teashop.khanhs_tea_bot.entity.Product;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class GeminiIntentService {

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public IntentResult parseIntent(String userText, List<Product> products) {
        IntentResult unknown = new IntentResult();
        unknown.setIntent("CHITCHAT");

        try {
            if (apiKey == null || apiKey.isBlank()) return unknown;

            String menu = products.stream()
                    .map(p -> p.getId() + " - " + p.getName())
                    .limit(60)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            String prompt = """
                    Bạn là trợ lý ảo của TeaShop. Phân tích câu chat và trả về JSON thuần.
                    
                    LUẬT INTENT:
                    - ADD_ITEM: Khách muốn mua/thêm món (VD: "thêm 1 ly TS01").
                    - CHECKOUT: Khách muốn tính tiền/thanh toán hoặc đưa thông tin: Tên, SĐT, Địa chỉ.
                    - SHOW_MENU: Khách muốn xem menu (VD: "menu có gì", "cho xem menu").
                    - SHOW_CART: Xem giỏ hàng.
                    - CLEAR: Xóa giỏ.
                    - CHITCHAT: Chào hỏi, hỏi giờ mở cửa, phí ship, địa chỉ quán (dựa trên context).
                    
                    SCHEMA JSON:
                    {"intent":"...","productId":null,"size":null,"quantity":null,"customerName":null,"customerPhone":null,"address":null,"response":"Câu trả lời tự nhiên"}
                    
                    Menu: %s
                    User: %s
                    """.formatted(menu, userText);

            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            body.put("generationConfig", Map.of("responseMimeType", "application/json"));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            String json = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText().trim();

            return objectMapper.readValue(json, IntentResult.class);
        } catch (Exception e) {
            log.error("Gemini parse error", e);
            return unknown;
        }
    }
}