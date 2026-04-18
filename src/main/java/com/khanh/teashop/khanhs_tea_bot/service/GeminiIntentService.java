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
        unknown.setIntent("UNKNOWN");

        if (userText.length() > 200) {
            return unknown;
        }

        try {
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("GEMINI_API_KEY is empty");
                return unknown;
            }

            String menu = products.stream()
                    .map(p -> p.getId() + " - " + p.getName())
                    .limit(60)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            String prompt = """
                    Trả về JSON thuần, không markdown.
                    Schema:
                    {"intent":"SHOW_MENU|ADD_ITEM|SHOW_CART|CHECKOUT|CLEAR|UNKNOWN","productId":null,"size":null,"quantity":null,"customerName":null,"customerPhone":null}
                    
                    Menu:
                    %s
                    
                    User:
                    %s
                    """.formatted(menu, userText);

            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(Map.of(
                    "parts", List.of(Map.of("text", prompt))
            )));
            body.put("generationConfig", Map.of(
                    "responseMimeType", "application/json"
            ));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            log.info("Gemini raw response: {}", response);

            JsonNode root = objectMapper.readTree(response);
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode() || textNode.asText().isBlank()) {
                return unknown;
            }

            String json = textNode.asText().trim().replace("```json", "").replace("```", "").trim();
            IntentResult parsed = objectMapper.readValue(json, IntentResult.class);
            if (parsed.getIntent() == null || parsed.getIntent().isBlank()) {
                return unknown;
            }
            return parsed;
        } catch (Exception exception) {
            log.error("Gemini parse error", exception);
            return unknown;
        }
    }

    public String answerFreelance(String userText) {
        try {
            String prompt = """
            Bạn là nhân viên quán trà sữa Khánh.
            Nếu khách hỏi ngoài chủ đề (không liên quan trà sữa), hãy:
            1. Trả lời thân thiện, tự nhiên
            2. Nhẹ nhàng gợi ý về menu trà sữa

            Ví dụ: Khách hỏi "tôi muốn ăn cơm"
            → Bot: "Dạ, cơm thì chúng tôi chưa có ạ. Nhưng bạn thử trà sữa Khánh xem, rất ngon và tươi mát luôn! 🍵"

            Câu hỏi: %s
            """.formatted(userText);

            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(Map.of(
                    "parts", List.of(Map.of("text", prompt))
            )));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
        } catch (Exception e) {
            log.warn("Freelance answer failed", e);
            return null;
        }
    }
}