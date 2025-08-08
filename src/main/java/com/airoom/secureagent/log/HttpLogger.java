package com.airoom.secureagent.log;

import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.util.CryptoUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;


/*
- 전송 대상 URL을 설정값으로 분리
  - JVM 옵션: aidt.baseUrl / aidt.logPath
  - 환경변수: AIDT_BASE_URL / AIDT_LOG_PATH
  - 기본값: StatusServer 동적 포트 기반 http://localhost:<port>/log
- 연결/읽기 타임아웃 추가 (5s)
- 엔드포인트 로깅 개선 (성공/실패 시 전송 대상 출력)
- 본문 AES 암호화 전송 로직 유지

사용 예:
- 개발: 기본값 사용 (로컬 내장 서버)
- 운영(HTTPS 전환 시):
  - JVM: -Daidt.baseUrl=https://aidt.example.com -Daidt.logPath=/log
  - 또는 ENV: AIDT_BASE_URL=https://aidt.example.com, AIDT_LOG_PATH=/log

테스트 계획:
1) StatusServer 기동 후 sendLog 호출 → 로컬 엔드포인트로 전송 성공 로그 확인
2) JVM 옵션으로 baseUrl 변경해 테스트 엔드포인트로 전송 확인
3) 네트워크 차단/지연 시 타임아웃(5s) 동작 확인
*/
public class HttpLogger {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    // 우선순위: JVM 시스템 프로퍼티 → 환경변수 → 로컬 기본값
    private static String getBaseUrl() {
        String prop = System.getProperty("aidt.baseUrl");
        if (prop != null && !prop.isBlank()) return stripTrailingSlash(prop);

        String env = System.getenv("AIDT_BASE_URL");
        if (env != null && !env.isBlank()) return stripTrailingSlash(env);

        // 기본: 로컬 내장 서버 (동적 포트)
        int port = StatusServer.getRunningPort();
        return "http://localhost:" + port;
    }

    // 로그 엔드포인트 경로도 분리(기본: /log)
    private static String getLogPath() {
        String prop = System.getProperty("aidt.logPath");
        if (prop != null && !prop.isBlank()) return ensureLeadingSlash(prop);

        String env = System.getenv("AIDT_LOG_PATH");
        if (env != null && !env.isBlank()) return ensureLeadingSlash(env);

        return "/log";
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String ensureLeadingSlash(String s) {
        return s.startsWith("/") ? s : "/" + s;
    }

    // HTTP/HTTPS 전송 (본문은 AES로 암호화)
    public static void sendLog(String message) {
        try {
            String endpoint = getBaseUrl() + getLogPath();
            URL url = new URL(endpoint);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            String encryptedMessage = CryptoUtil.encrypt(message);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(encryptedMessage.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                System.out.println("[클라이언트] 로그 전송 성공 → " + endpoint);
            } else {
                System.out.println("[클라이언트] 로그 전송 실패 (" + code + ") → " + endpoint);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
