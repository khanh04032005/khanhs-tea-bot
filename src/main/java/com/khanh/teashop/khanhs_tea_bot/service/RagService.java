package com.khanh.teashop.khanhs_tea_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final QdrantService qdrantService;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String answerMenuQuestion(String question) {
        // Tăng giới hạn lên 10 để Gemini có nhiều dữ liệu so sánh hơn, tránh trả lời sai
        int limit = 10;

        String q = question.toLowerCase();
        boolean faqMode = q.contains("mở cửa") || q.contains("giờ") || q.contains("ship") || q.contains("phí") || q.contains("địa chỉ");

        try {
            List<String> contexts = faqMode
                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
                    : qdrantService.searchTextContexts(question, limit);

            if (contexts.isEmpty()) {
                contexts = qdrantService.searchTextContexts(question, limit);
            }

            // Nếu vẫn không có dữ liệu trong Database
            if (contexts.isEmpty()) {
                return "Hiện tại mình chưa có thông tin về vấn đề này. Bạn xem /menu hoặc hỏi về giờ mở cửa nhé!";
            }

            String joinedContext = String.join("\n\n", contexts);

            // Cải tiến Prompt: Ép AI trả lời có tâm hơn
            String prompt = """
                    BẠN LÀ NHÂN VIÊN TƯ VẤN CỦA QUÁN KHANHS TEA SHOP.
                                          NHIỆM VỤ: Trả lời câu hỏi của khách dựa trên CONTEXT được cung cấp.
                    
                                            QUY TẮC:
                                            1. Trả lời bằng tiếng Việt, ngắn gọn, tự nhiên, xưng hô "mình" - "bạn".
                                            2. Nếu khách hỏi về thực đơn (menu), giờ mở cửa, phí ship, địa chỉ: Hãy lấy thông tin chính xác từ CONTEXT.
                                            3. Nếu CONTEXT không có thông tin khách cần: Hãy nói "Dạ hiện tại mình chưa có thông tin chính xác về món/vấn đề này, bạn xem tạm /menu hoặc nhắn 'tư vấn' để mình hỗ trợ thêm nhé".
                                            4. TUYỆT ĐỐI không được nói câu "Chỉ hỗ trợ đặt trà sữa" nếu khách đang hỏi về chính thông tin trà sữa của quán.
                    
                                            CONTEXT:
                                            %s
                    
                                            CÂU HỎI CỦA KHÁCH:
                                            %s
                    
                                            TRẢ LỜI:
                """.formatted(joinedContext, question);

            return callGemini(prompt);

        } catch (Exception exception) {
            log.error("RAG answer failed", exception);
            return "Xin lỗi, mình đang gặp chút trục trặc khi tra cứu menu. Bạn xem tạm /menu nhé!";
        }
    }

    // Tách hàm gọi Gemini ra cho sạch code
    private String callGemini(String prompt) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String raw = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(raw);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText().trim();
        } catch (Exception e) {
            return "Máy chủ đang bận, bạn đợi xíu nhé!";
        }
    }

    // Phần gợi ý Topping này Khánh làm rất tốt, giữ nguyên hoặc sửa nhẹ cho tự nhiên
    public String suggestAddOns(List<String> currentProducts) {
        List<String> suggestions = new java.util.ArrayList<>();
        if (currentProducts.stream().anyMatch(p -> p.startsWith("TS"))) {
            suggestions.add("Thêm Trân Châu Đen (TOP01) hoặc Kem Tươi (TOP06) cho ngon nè!");
        }
        if (currentProducts.stream().anyMatch(p -> p.startsWith("DX"))) {
            suggestions.add("Đá xay kèm Thạch Cà Chua (TOP03) là hết sảy luôn!");
        }
        return suggestions.isEmpty() ? "" : "💡 Gợi ý cho bạn: " + String.join(" ", suggestions);
    }
}