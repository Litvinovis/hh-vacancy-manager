package com.hh.gui.service;

import com.hh.gui.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Sends messages via Telegram Bot API.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final String TG_API = "https://api.telegram.org/bot%s/sendMessage";

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.chat-id:}")
    private String chatId;

    public boolean send(String message) {
        if (botToken == null || botToken.isEmpty()) {
            log.warn("Токен Telegram-бота не настроен");
            return false;
        }
        if (chatId == null || chatId.isEmpty()) {
            log.warn("ID чата Telegram не настроен");
            return false;
        }

        try {
            String url = String.format(TG_API, botToken);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&parse_mode=HTML&disable_web_page_preview=true";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                log.info("Сообщение Telegram успешно отправлено");
                return true;
            } else {
                log.error("Ошибка Telegram API {}: {}", code, HttpUtil.readBody(conn, code));
                return false;
            }
        } catch (Exception e) {
            log.error("Не удалось отправить сообщение Telegram: {}", e.getMessage());
            return false;
        }
    }
}
