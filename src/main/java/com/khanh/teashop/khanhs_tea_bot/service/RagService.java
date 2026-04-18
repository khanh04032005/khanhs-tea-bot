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
        String q = question.toLowerCase();

        // Phân loại câu hỏi để limit context đúng
        boolean isMenuQuery = q.contains("menu") || q.contains("order") || q.contains("gì") || q.contains("có gì");
        boolean isFaqQuery = isFaqQuestion(question);
        boolean isProductCode = question.toUpperCase().matches(".*\\b[A-Z]{2,5}\\d{2}\\b.*");

        int limit = isProductCode ? 1 : (isMenuQuery ? 30 : 10);

        try {
            List<String> contexts = searchContexts(question, limit, isFaqQuery, isMenuQuery);

            if (contexts.isEmpty()) {
                return "";
            }

            String answer = callGeminiApi(question, contexts, isMenuQuery);

            if (answer.isBlank()) {
                return formatFallbackResponse(contexts);
            }

            return answer;
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
            log.warn("Gemini rate limited, using fallback");
            return handleFallback(question, limit, isFaqQuery, isMenuQuery);
        } catch (Exception exception) {
            log.error("RAG answer failed", exception);
            return handleFallback(question, limit, isFaqQuery, isMenuQuery);
        }
    }

    private boolean isFaqQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("mở cửa") || q.contains("giờ") || q.contains("ship") || q.contains("phí");
    }

    private List<String> searchContexts(String question, int limit, boolean isFaqQuery, boolean isMenuQuery) {
        // FAQ question - search trong FAQ source
        if (isFaqQuery) {
            List<String> contexts = qdrantService.searchTextContextsBySource(question, limit, "faq");
            if (!contexts.isEmpty()) return contexts;
        }

        // Menu query - search chỉ trong menu items
        if (isMenuQuery) {
            List<String> contexts = qdrantService.searchTextContextsBySource(question, limit, "menu");
            if (!contexts.isEmpty()) return contexts;
        }

        // Default search
        return qdrantService.searchTextContexts(question, limit);
    }

    private String callGeminiApi(String question, List<String> contexts, boolean isMenuQuery) throws Exception {
        String joinedContext = String.join("\n\n", contexts);

        // Sửa lại System Prompt cho có "gu" phục vụ
        String systemPrompt = isMenuQuery
                ? "Bạn là nhân viên tiệm trà sữa Khánh. Hãy liệt kê tên món và giá tiền một cách thân thiện, đẹp mắt. Tuyệt đối không đưa mã sản phẩm (như TS01, DX01) hay trạng thái true/false vào câu trả lời."
                : "Bạn là trợ lý quán trà sữa Khánh. Trả lời bằng tiếng Việt, ngắn gọn, tự nhiên như người thật dựa trên dữ liệu. Nếu không có thông tin, hãy báo là quán chưa có món này ạ.";

        String prompt = """
            %s

            Dữ liệu quán (Context):
            %s

            Câu hỏi của khách:
            %s
            """.formatted(systemPrompt, joinedContext, question);

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
        return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
    }

    private String formatFallbackResponse(List<String> contexts) {
        if (contexts.isEmpty()) {
            return "Dạ hiện tại mình chưa tìm thấy thông tin này, bạn đợi xíu để mình kiểm tra lại nhé!";
        }

        StringBuilder sb = new StringBuilder("Dạ, đây là một số thông tin mình tìm được:\n\n");
        for (int i = 0; i < contexts.size() && i < 30; i++) {
            String context = contexts.get(i);

            // Xóa sạch các field database không cần
            String cleaned = context.replaceAll("(?i)Mã:\\s*[A-Z0-9]+,?\\s*", "")  // Xóa Mã: TS01,
                    .replaceAll("(?i)Danh mục:\\s*[^,]+,?\\s*", "")  // Xóa Danh mục
                    .replaceAll("(?i)Còn bán:\\s*(true|false),?\\s*", "")  // Xóa Còn bán
                    .replaceAll("(?i)available:\\s*(true|false),?\\s*", "")  // Xóa available
                    .replaceAll("Tên:\\s*", "")  // Xóa "Tên:"
                    .replaceAll("Giá M:\\s*", "M: ")  // Format giá
                    .replaceAll("Giá L:\\s*", "L: ")
                    .replaceAll("\\s+", " ")
                    .replaceAll(",\\s*,", ",")  // Fix dấu phẩy kép
                    .trim();

            if (!cleaned.isEmpty() && !cleaned.matches("^[,:\\s]+$")) {
                sb.append("✨ ").append(cleaned).append("\n");
            }
        }
        sb.append("\nBạn cần đặt món nào thì nhắn mình nhé! 🍵");
        return sb.toString();
    }

    private String handleFallback(String question, int limit, boolean isFaqQuery, boolean isMenuQuery) {
        List<String> contexts = searchContexts(question, limit, isFaqQuery, isMenuQuery);
        return formatFallbackResponse(contexts);
    }
}