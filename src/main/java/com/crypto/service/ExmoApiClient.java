package com.crypto.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class ExmoApiClient {

    @Value("${exmo.apiKey}")
    private String apiKey;

    @Value("${exmo.secret}")
    private String secret;

    private static final String API_URL = "https://api.exmo.com/v1.1/";

    // Создаем атомарный счетчик для nonce.
    // Он инициализируется текущим временем и будет безопасно увеличиваться
    // при каждом запросе, даже в многопоточной среде.
    private final AtomicLong nonce = new AtomicLong(System.currentTimeMillis());

    public String post(String method, Map<String, String> params) throws Exception {
        // Получаем новое, уникальное и строго возрастающее значение nonce
        String nonceValue = String.valueOf(this.nonce.incrementAndGet());
        params.put("nonce", nonceValue);

        String postData = buildQuery(params);
        String sign = hmacSha512(postData, secret);

        URL url = new URL(API_URL + method);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Key", apiKey);
        conn.setRequestProperty("Sign", sign);
        conn.setConnectTimeout(10000); // 10 секунд таймаут на подключение
        conn.setReadTimeout(10000);    // 10 секунд таймаут на чтение

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes());
            os.flush();
        }

        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null)
                response.append(inputLine);
            return response.toString();
        }
    }

    private String hmacSha512(String data, String secret) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA512");
        hmac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA512"));
        byte[] hash = hmac.doFinal(data.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hash)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet())
            result.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        // Исправлена потенциальная ошибка, если params пустые
        if (result.length() > 0) {
            return result.substring(0, result.length() - 1);
        }
        return "";
    }
}
