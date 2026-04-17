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
        unknown.setIntent("CHITCHAT"); // Mặc định là trò chuyện nếu không hiểu

        try {
            if (apiKey == null || apiKey.isBlank()) return unknown;

            String menu = products.stream()
                    .map(p -> p.getId() + " - " + p.getName())
                    .limit(60)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            // PROMPT NÂNG CẤP: ÉP AI BÓC TÁCH ĐỊA CHỈ VÀ PHÂN LOẠI CHUẨN
            String prompt = """
                    Bạn là bộ não của TeaShop Bot. Hãy phân tích câu chat của khách và trả về JSON thuần.
                    
                    LUẬT INTENT:
                    - ADD_ITEM: Khách muốn mua/thêm món (Mặc định size M, quantity 1 nếu khách không nói).
                    - CHECKOUT: Khách muốn tính tiền hoặc cung cấp (Tên, SĐT, Địa chỉ).
                    - SHOW_MENU: Khách muốn xem menu.
                    - SHOW_CART: Khách hỏi giỏ hàng có gì.
                    - CLEAR: Khách muốn xóa giỏ.
                    - CHITCHAT: Chào hỏi, hỏi giờ mở cửa, hoặc nói chuyện không liên quan.
                    
                    SCHEMA JSON TRẢ VỀ:
                    {"intent":"...","productId":null,"size":null,"quantity":null,"customerName":null,"customerPhone":null,"address":null,"response":"Câu trả lời ngắn gọn cho khách"}
                    
                    Menu sản phẩm:
                    %s
                    
                    Câu chat của khách:
                    %s
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

            // Ép kiểu về IntentResult (Nhớ đảm bảo class IntentResult đã có field address và response)
            return objectMapper.readValue(json, IntentResult.class);
        } catch (Exception exception) {
            log.error("Gemini parse error", exception);
            return unknown;
        }
    }
}