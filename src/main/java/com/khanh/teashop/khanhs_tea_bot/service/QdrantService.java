package com.khanh.teashop.khanhs_tea_bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khanh.teashop.khanhs_tea_bot.config.QdrantProperties;
import com.khanh.teashop.khanhs_tea_bot.dto.rag.RagDocument;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantService {

    private final QdrantProperties qdrantProperties;
    private final GeminiEmbeddingService geminiEmbeddingService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void ensureCollection() {
        String collectionUrl = qdrantProperties.getUrl() + "/collections/" + qdrantProperties.getCollection();

        // 1. Tạo Collection
        Map<String, Object> vectors = new HashMap<>();
        vectors.put("size", qdrantProperties.getVectorSize());
        vectors.put("distance", "Cosine");

        Map<String, Object> createBody = new HashMap<>();
        createBody.put("vectors", vectors);

        try {
            restTemplate.exchange(collectionUrl, HttpMethod.PUT, new HttpEntity<>(createBody, headers()), String.class);
            log.info("✅ Qdrant collection created");

            // 2. TẠO INDEX CHO FIELD 'source' (BẮT BUỘC ĐỂ FILTER KHÔNG LỖI)
            String indexUrl = collectionUrl + "/index";
            Map<String, Object> indexBody = new HashMap<>();
            indexBody.put("field_name", "source");
            indexBody.put("field_schema", "keyword");

            restTemplate.exchange(indexUrl, HttpMethod.PUT, new HttpEntity<>(indexBody, headers()), String.class);
            log.info("✅ Created Payload Index for 'source'");

        } catch (org.springframework.web.client.HttpClientErrorException.Conflict conflict) {
            log.info("ℹ️ Qdrant collection already exists");
        } catch (Exception e) {
            log.error("❌ Qdrant setup failed", e);
        }
    }
    public void upsertDocuments(List<RagDocument> documents) {
        String url = qdrantProperties.getUrl()
                + "/collections/" + qdrantProperties.getCollection()
                + "/points?wait=true";

        List<Map<String, Object>> points = new ArrayList<>();
        for (RagDocument document : documents) {
            List<Double> vector = geminiEmbeddingService.embed(document.getText());

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", document.getTitle());
            payload.put("text", document.getText());
            payload.put("category", document.getCategory());
            payload.put("source", document.getSource());

            String pointId = UUID.nameUUIDFromBytes(document.getId().getBytes(StandardCharsets.UTF_8)).toString();

            Map<String, Object> point = new HashMap<>();
            point.put("id", pointId);
            point.put("vector", vector);
            point.put("payload", payload);

            points.add(point);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("points", points);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers()), String.class);
            log.info("Qdrant upserted {} documents", points.size());
        } catch (Exception exception) {
            log.error("Qdrant upsert failed", exception);
            throw new IllegalStateException("Qdrant upsert failed");
        }
    }

    public List<String> searchTextContexts(String question, int limit) {
        return searchTextContextsInternal(question, limit, null);
    }

    public List<String> searchTextContextsBySource(String question, int limit, String source) {
        return searchTextContextsInternal(question, limit, source);
    }

    private List<String> searchTextContextsInternal(String question, int limit, String source) {
        try {
            List<Double> vector = geminiEmbeddingService.embed(question);

            String url = qdrantProperties.getUrl()
                    + "/collections/" + qdrantProperties.getCollection()
                    + "/points/search";

            Map<String, Object> body = new HashMap<>();
            body.put("vector", vector);
            body.put("limit", limit);
            body.put("with_payload", true);

            if (source != null && !source.isBlank()) {
                body.put("filter", Map.of(
                        "must", List.of(
                                Map.of("key", "source", "match", Map.of("value", source))
                        )
                ));
            }

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode result = root.path("result");

            List<String> contexts = new ArrayList<>();
            for (JsonNode node : result) {
                String text = node.path("payload").path("text").asText("");
                if (!text.isBlank()) {
                    contexts.add(text);
                }
            }
            return contexts;
        } catch (Exception exception) {
            log.warn("Qdrant search failed, returning empty", exception);
            return new ArrayList<>();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String apiKey = qdrantProperties.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        return headers;
    }
}