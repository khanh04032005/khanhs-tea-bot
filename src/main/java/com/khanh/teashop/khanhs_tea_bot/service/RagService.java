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
        boolean faqMode = isFaqQuestion(question);

        try {
            List<String> contexts = searchContexts(question, limit, faqMode);

            if (contexts.isEmpty()) {
                return "";
            }

            String answer = callGeminiApi(question, contexts);

            if (answer.isBlank()) {
                return formatFallbackResponse(contexts);
            }

            return answer;
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
            log.warn("Gemini rate limited, using fallback");
            return handleFallback(question, limit, faqMode);
        } catch (Exception exception) {
            log.error("RAG answer failed", exception);
            return handleFallback(question, limit, faqMode);
        }
    }

    private boolean isFaqQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("mở cửa") || q.contains("giờ") || q.contains("ship") || q.contains("phí");
    }

    private List<String> searchContexts(String question, int limit, boolean faqMode) {
        List<String> contexts = faqMode
                ? qdrantService.searchTextContextsBySource(question, limit, "faq")
                : qdrantService.searchTextContexts(question, limit);

        if (contexts.isEmpty() && faqMode) {
            contexts = qdrantService.searchTextContexts(question, limit);
        }

        return contexts;
    }

    private String callGeminiApi(String question, List<String> contexts) throws Exception {
        String joinedContext = String.join("\n\n", contexts);

        String prompt = """
            Bạn là trợ lý quán trà sữa.
            Trả lời bằng tiếng Việt, ngắn gọn, đúng theo dữ liệu context.
            Nếu không đủ dữ liệu, nói rõ là chưa có thông tin.

            Context:
            %s

            Câu hỏi khách:
            %s
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
        return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
    }

    private String formatFallbackResponse(List<String> contexts) {
        if (contexts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("Thông tin mình tìm được:\n");
        for (int i = 0; i < contexts.size() && i < 5; i++) {
            String context = contexts.get(i);
            String cleaned = context.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 150) {
                cleaned = cleaned.substring(0, 150) + "...";
            }
            sb.append("• ").append(cleaned).append("\n");
        }
        return sb.toString();
    }

    private String handleFallback(String question, int limit, boolean faqMode) {
        List<String> contexts = searchContexts(question, limit, faqMode);
        return formatFallbackResponse(contexts);
    }
}