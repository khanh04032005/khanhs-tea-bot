//package com.khanh.teashop.khanhs_tea_bot.service;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.HttpClientErrorException;
//import org.springframework.web.client.RestTemplate;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class RagService {
//
//    private final QdrantService qdrantService;
//
//    @Value("${gemini.api-key:}")
//    private String apiKey;
//
//    @Value("${gemini.model:gemini-2.0-flash}")
//    private String model;
//
//    private final RestTemplate restTemplate = new RestTemplate();
//    private final ObjectMapper objectMapper = new ObjectMapper();
//
//    public String answerMenuQuestion(String question) {
//        int limit = question.toUpperCase().matches(".*\\b[A-Z]{2,5}\\d{2}\\b.*") ? 1 : 5;
//
//        String q = question.toLowerCase();
//        boolean faqMode = q.contains("mở cửa") || q.contains("giờ") || q.contains("ship") || q.contains("phí");
//
//        try {
//            List<String> contexts = faqMode
//                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
//                    : qdrantService.searchTextContexts(question, limit);
//
//            if (contexts.isEmpty() && faqMode) {
//                contexts = qdrantService.searchTextContexts(question, limit);
//            }
//
//            if (contexts.isEmpty()) {
//                return "";
//            }
//
//            String joinedContext = String.join("\n\n", contexts);
//
//            String prompt = """
//                Bạn là trợ lý quán trà sữa.
//                Trả lời bằng tiếng Việt, ngắn gọn, đúng theo dữ liệu context.
//                Nếu không đủ dữ liệu, nói rõ là chưa có thông tin.
//
//                Context:
//                %s
//
//                Câu hỏi khách:
//                %s
//                """.formatted(joinedContext, question);
//
//            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
//                    + model
//                    + ":generateContent?key=" + apiKey;
//
//            Map<String, Object> body = new HashMap<>();
//            body.put("contents", List.of(
//                    Map.of("parts", List.of(Map.of("text", prompt)))
//            ));
//
//            HttpHeaders headers = new HttpHeaders();
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            String raw = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
//            JsonNode root = objectMapper.readTree(raw);
//            String answer = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
//
//            if (answer.isBlank()) {
//                return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
//            }
//            return answer;
//        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
//            List<String> contexts = faqMode
//                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
//                    : qdrantService.searchTextContexts(question, limit);
//
//            if (contexts.isEmpty() && faqMode) {
//                contexts = qdrantService.searchTextContexts(question, limit);
//            }
//
//            if (contexts.isEmpty()) {
//                return "";
//            }
//            return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
//        } catch (Exception exception) {
//            log.error("RAG answer failed", exception);
//
//            List<String> contexts = faqMode
//                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
//                    : qdrantService.searchTextContexts(question, limit);
//
//            if (contexts.isEmpty() && faqMode) {
//                contexts = qdrantService.searchTextContexts(question, limit);
//            }
//
//            if (contexts.isEmpty()) {
//                return "";
//            }
//            return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
//        }
//    }
//}
//


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
        int limit = question.toUpperCase().matches(".*\\b[A-Z]{2,5}\\d{2}\\b.*") ? 1 : 5;

        String q = question.toLowerCase();
        boolean faqMode = q.contains("mở cửa") || q.contains("giờ") || q.contains("ship") || q.contains("phí");

        try {
            List<String> contexts = faqMode
                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
                    : qdrantService.searchTextContexts(question, limit);

            if (contexts.isEmpty() && faqMode) {
                contexts = qdrantService.searchTextContexts(question, limit);
            }

            if (contexts.isEmpty()) {
                return "";
            }

            String joinedContext = String.join("\n\n", contexts);

            // Prompt cải tiến: strict, structured, intelligent
            String prompt = """
                    BẠN LÀ TRỢ LÝ QUÁN TRÀ SỮA KHANHS TEA SHOP.
                    **LUẬT LỆ TRẢ LỜI:**
                    1. CHỈ được trả lời dựa trên thông tin trong CONTEXT
                    2. KHÔNG được tự bịa hoặc suy đoán thêm thông tin ngoài CONTEXT
                    3. Nếu câu hỏi không liên quan đến menu hoặc FAQ trong CONTEXT, trả lời:
                       "Mình chỉ biết về menu trà sữa, bạn tìm gì vậy?"
                    4. Trả lời bằng tiếng Việt, ngắn gọn, tự nhiên
                    5. Nếu khách hỏi về sản phẩm, LUÔN gợi ý thêm các sản phẩm liên quan trong CONTEXT (nếu có)
                    
                    **FORMAT TRẢ LỜI:**
                    - Trả lời ngắn gọn, đúng trọng tâm
                    - Nếu có gợi ý sản phẩm, liệt kê theo format:
                      Mã - Tên - Giá (M/L nếu có)
                    
                    Ví dụ:
                    "Bạn thích trà sữa? Mình gợi ý: TS01 - Trà sữa truyền thống (35k/M - 45k/L), TS02 - Trà sữa matcha (35k/M - 45k/L)"
                    
                    **LƯU Ý:**
                    - Nếu thông tin không có trong CONTEXT, phải từ chối trả lời
                    - Không thêm mô tả ngoài dữ liệu có sẵn
                    
                    **CONTEXT:**
                    ${joinedContext}
                    
                    **CÂU HỎI KHÁCH:**
                    ${question}
                    
                    **TRẢ LỜI:**
                """.formatted(joinedContext, question);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model
                    + ":generateContent?key=" + apiKey;

            Map<String, Object> body = new HashMap<>();
            body.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String raw = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(raw);
            String answer = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();

            if (answer.isBlank()) {
                return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
            }
            return answer;

        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
            log.warn("Gemini rate limited, fallback to context only");
            List<String> contexts = faqMode
                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
                    : qdrantService.searchTextContexts(question, limit);

            if (contexts.isEmpty() && faqMode) {
                contexts = qdrantService.searchTextContexts(question, limit);
            }

            if (contexts.isEmpty()) {
                return "";
            }
            return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);

        } catch (Exception exception) {
            log.error("RAG answer failed", exception);

            List<String> contexts = faqMode
                    ? qdrantService.searchTextContextsBySource(question, limit, "faq")
                    : qdrantService.searchTextContexts(question, limit);

            if (contexts.isEmpty() && faqMode) {
                contexts = qdrantService.searchTextContexts(question, limit);
            }

            if (contexts.isEmpty()) {
                return "";
            }
            return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
        }
    }

    /**
     * Tóm tắt thông tin đơn hàng + gợi ý giao hàng
     */
    public String summarizeOrderForShipping(Long orderId, String customerName, long totalAmount, List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 **TÓM TẮT ĐƠN HÀNG**\n");
        sb.append("Mã đơn: #").append(orderId).append("\n");
        sb.append("Khách: ").append(customerName).append("\n");
        sb.append("Tổng tiền: ").append(totalAmount).append(" VND\n");
        sb.append("Chi tiết:\n");

        for (Map<String, Object> item : items) {
            sb.append("  - ").append(item.get("productName"))
                    .append(" (").append(item.get("size")).append(") x")
                    .append(item.get("quantity")).append(" = ")
                    .append(item.get("lineTotal")).append(" VND\n");
        }

        sb.append("\n📦 **HƯỚNG DẪN GIAO HÀNG:**\n");
        sb.append("1. Chuẩn bị nguyên liệu\n");
        sb.append("2. Chế biến theo đơn\n");
        sb.append("3. Đóng gói cẩn thận (giữ nhiệt độ)\n");
        sb.append("4. Liên hệ khách xác nhận địa chỉ\n");
        sb.append("5. Giao hàng trong 30 phút\n");

        return sb.toString();
    }

    /**
     * Gợi ý upsell/cross-sell dựa trên đơn hiện tại
     */
    public String suggestAddOns(List<String> currentProducts) {
        List<String> suggestions = new java.util.ArrayList<>();

        // Nếu khách đã mua trà sữa, gợi ý topping
        if (currentProducts.stream().anyMatch(p -> p.startsWith("TS"))) {
            suggestions.add("Muốn thêm topping? TOP01 (Trân Châu Đen - 5k), TOP06 (Kem Tươi - 8k)");
        }

        // Nếu mua đá xay, gợi ý topping
        if (currentProducts.stream().anyMatch(p -> p.startsWith("DX"))) {
            suggestions.add("Kèm topping thêm vào? TOP03 (Thạch Cà Chua - 4k)");
        }

        // Nếu chỉ mua cà phê, gợi ý trà
        if (currentProducts.stream().anyMatch(p -> p.startsWith("CF"))
                && !currentProducts.stream().anyMatch(p -> p.startsWith("TS"))) {
            suggestions.add("Thêm trà sữa? TS01 (35k/M), TS02 (35k/M)");
        }

        return suggestions.isEmpty() ? "" : "💡 " + String.join("\n💡 ", suggestions);
    }
}