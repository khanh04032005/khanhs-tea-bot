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
import org.springframework.web.client.HttpClientErrorException;
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
            String answer = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();

            if (answer.isBlank()) {
                return "Thông tin mình tìm được:\n- " + String.join("\n- ", contexts);
            }
            return answer;
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests exception) {
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
}