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

            String prompt = """
    BẠN LÀ BỘ NÃO CỦA HỆ THỐNG TRÀ SỮA KHANHS TEA SHOP.
    NHIỆM VỤ: Phân tích tin nhắn và trả về DUY NHẤT một chuỗi JSON hợp lệ. Không được thêm văn bản thừa ngoài JSON.

    **LUẬT PHÂN LOẠI INTENT:**
    1. "ADD_ITEM": Khách muốn mua, thêm món, chọn size (VD: "lấy 1 TS01", "thêm ly trà sữa").
    2. "CHECKOUT": Khách muốn tính tiền, thanh toán hoặc cung cấp địa chỉ/SĐT giao hàng.
    3. "SHOW_MENU": Khách muốn xem danh sách món.
    4. "SHOW_CART": Khách muốn kiểm tra giỏ hàng hiện tại.
    5. "CLEAR": Khách muốn xóa/hủy giỏ hàng.
    6. "CHITCHAT": Chào hỏi hoặc hỏi về thông tin quán (giờ mở cửa, phí ship) dựa trên CONTEXT.

    **YÊU CẦU DỮ LIỆU:**
    - Nếu không có thông tin productId, customerName, customerPhone, address -> Để giá trị là null.
    - Size mặc định là "M" nếu khách không nói. Quantity mặc định là 1.
    - Trường "response" là câu trả lời tự nhiên, thân thiện.

    **CONTEXT:**
    ${joinedContext}

    **CẤU TRÚC JSON:**
    {
      "intent": "ADD_ITEM" | "CHECKOUT" | "SHOW_MENU" | "SHOW_CART" | "CLEAR" | "CHITCHAT",
      "productId": "string",
      "size": "M" | "L",
      "quantity": number,
      "customerName": "string",
      "customerPhone": "string",
      "address": "string",
      "response": "string"
    }

    **CÂU HỎI CỦA KHÁCH:**
    ${question}

    **JSON TRẢ LỜI:**
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