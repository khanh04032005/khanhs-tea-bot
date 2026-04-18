package com.khanh.teashop.khanhs_tea_bot.service;

import com.khanh.teashop.khanhs_tea_bot.telegram.TelegramBotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramNotifyService {

    private final TelegramBotProperties telegramBotProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public void send(Long chatId, String message) {
        if (chatId == null || message == null || message.isBlank()) {
            return;
        }

        String url = "https://api.telegram.org/bot" + telegramBotProperties.getToken() + "/sendMessage";

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId.toString());
        body.put("text", message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
        } catch (Exception ex) {
            log.error("Failed to send Telegram notify. chatId={}", chatId, ex);
        }
    }
}