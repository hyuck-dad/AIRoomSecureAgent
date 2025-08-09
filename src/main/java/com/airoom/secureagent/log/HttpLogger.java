package com.airoom.secureagent.log;

import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.util.CryptoUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.ThreadLocalRandom;

public class HttpLogger {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static volatile OfflineLogStore offlineStore;

    public static void setOfflineStore(OfflineLogStore store) { offlineStore = store; }

    private static String getBaseUrl() {
        String prop = System.getProperty("aidt.baseUrl");
        if (prop != null && !prop.isBlank()) return stripTrailingSlash(prop);
        String env = System.getenv("AIDT_BASE_URL");
        if (env != null && !env.isBlank()) return stripTrailingSlash(env);
        int port = StatusServer.getRunningPort();
        return "http://localhost:" + port;
    }

    private static String getLogPath() {
        String prop = System.getProperty("aidt.logPath");
        if (prop != null && !prop.isBlank()) return ensureLeadingSlash(prop);
        String env = System.getenv("AIDT_LOG_PATH");
        if (env != null && !env.isBlank()) return ensureLeadingSlash(env);
        return "/log";
    }

    private static String stripTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String ensureLeadingSlash(String s) { return s.startsWith("/") ? s : "/" + s; }

    private static boolean shouldForceFail() {
        String always = System.getProperty("aidt.forceNetFail", System.getenv("AIDT_FORCE_NET_FAIL"));
        if (always != null && always.equalsIgnoreCase("always")) return true;

        String p = System.getProperty("aidt.forceNetFailPercent", System.getenv("AIDT_FORCE_NET_FAIL_PERCENT"));
        if (p != null) {
            try {
                int rate = Integer.parseInt(p);
                if (rate > 0 && ThreadLocalRandom.current().nextInt(100) < Math.min(rate, 100)) return true;
            } catch (NumberFormatException ignore) {}
        }
        return false;
    }

    private static int getConnectTimeout() {
        String v = System.getProperty("aidt.net.connectTimeoutMs", System.getenv("AIDT_NET_CONNECT_TIMEOUT_MS"));
        try { return (v != null) ? Integer.parseInt(v) : CONNECT_TIMEOUT_MS; } catch (Exception ignore) { return CONNECT_TIMEOUT_MS; }
    }
    private static int getReadTimeout() {
        String v = System.getProperty("aidt.net.readTimeoutMs", System.getenv("AIDT_NET_READ_TIMEOUT_MS"));
        try { return (v != null) ? Integer.parseInt(v) : READ_TIMEOUT_MS; } catch (Exception ignore) { return READ_TIMEOUT_MS; }
    }

    /** 성공 시 true, 실패 시 false (실패하면 오프라인 스풀로 적재) */
    public static boolean sendLog(String message) {

        // 테스트용 강제 실패
        if (shouldForceFail()) {
            try { if (offlineStore != null) offlineStore.append(message); } catch (Exception e) { e.printStackTrace(); }
            System.out.println("[HttpLogger] TEST force-fail → 스풀 적재");
            return false;
        }

        String endpoint = null;
        try {
            endpoint = getBaseUrl() + getLogPath();
            URL url = new URL(endpoint);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(getConnectTimeout());
            conn.setReadTimeout(getReadTimeout());
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
                return true;
            } else {
                System.out.println("[클라이언트] 로그 전송 실패 (" + code + ") → " + endpoint);
            }
        } catch (Exception e) {
            System.out.println("[클라이언트] 로그 전송 예외 → " + (endpoint == null ? "-" : endpoint) +
                    " / reason=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            if (offlineStore != null) {
                offlineStore.append(message); // ※ 디스크 암호화는 FileSpoolStore 설정에 따름
                System.out.println("[클라이언트] 네트워크 실패 → 오프라인 스풀 적재");
            }
        } catch (Exception ex) {
            System.err.println("[클라이언트] 오프라인 스풀 적재 실패: " + ex.getMessage());
        }
        return false;
    }

    /** JSON 전송: encrypt=true면 CryptoUtil.encrypt(json)을 text/plain으로 보냄 */
    public static boolean sendJson(String endpointPath, String json, boolean encrypt) {
        // 테스트용 강제 실패
        if (shouldForceFail()) {
            System.out.println("[HttpLogger] TEST force-fail(JSON)");
            return false;
        }

        String endpoint = null;
        try {
            endpoint = getBaseUrl() + ensureLeadingSlash(endpointPath);
            URL url = new URL(endpoint);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(getConnectTimeout());
            conn.setReadTimeout(getReadTimeout());
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            String bodyToSend;
            if (encrypt) {
                bodyToSend = CryptoUtil.encrypt(json);
                conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
            } else {
                bodyToSend = json;
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyToSend.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                System.out.println("[클라이언트] JSON 전송 성공 → " + endpoint);
                return true;
            } else {
                System.out.println("[클라이언트] JSON 전송 실패 (" + code + ") → " + endpoint);
            }
        } catch (Exception e) {
            System.out.println("[클라이언트] JSON 전송 예외 → " + (endpoint == null ? "-" : endpoint) +
                    " / reason=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // ※ 이벤트는 지금 스풀링하지 않음(정책상 실시간 검증 목적)
        return false;
    }


}
