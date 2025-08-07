package com.airoom.secureagent.log;

import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.util.CryptoUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpLogger {

    // HTTP 서버 로그 전송


    public static void sendLog(String message) {
        try {
            int port = StatusServer.getRunningPort(); // 동적으로 할당된 포트 가져오기
            URL url = new URL("http://localhost:" + port + "/log");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                // 암호화된 로그 전송
                String encryptedMessage = CryptoUtil.encrypt(message);
                os.write(encryptedMessage.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("[클라이언트] 로그 전송 성공: " + message);
            } else {
                System.out.println("[클라이언트] 로그 전송 실패, 응답 코드: " + responseCode);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
