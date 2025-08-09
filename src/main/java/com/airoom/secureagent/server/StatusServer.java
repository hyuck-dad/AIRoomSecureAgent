package com.airoom.secureagent.server;

import com.airoom.secureagent.util.CryptoUtil;
import com.sun.net.httpserver.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class StatusServer {

    private static int runningPort = -1;
    public static int getRunningPort() { return runningPort; }


    private static volatile Runnable flushCallback;
    public static void registerFlushCallback(Runnable cb) { flushCallback = cb; }

    private enum FailMode { OFF, HTTP_500, TIMEOUT, PERCENT }
    private static volatile FailMode failMode = FailMode.OFF;
    private static volatile long failUntil = 0L;
    private static volatile int failPercent = 0;  // 0~100
    private static volatile int sleepMs = 7000;   // timeout 시 서버가 일부러 지연하는 시간

    private static boolean isFailingNow() {
        long now = System.currentTimeMillis();
        if (failMode == FailMode.OFF) return false;
        if (failUntil > 0 && now > failUntil) { // 기간 끝나면 자동 해제
            failMode = FailMode.OFF; failPercent = 0; return false;
        }
        if (failMode == FailMode.PERCENT) {
            return ThreadLocalRandom.current().nextInt(100) < failPercent;
        }
        return true;
    }

    public static void startServer() throws Exception {
        int port = 4455;
        HttpServer server = null;

        while (port <= 65535) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                runningPort = port;
                break;
            } catch (BindException e) { port++; }
        }
        if (server == null) throw new RuntimeException("[SecureAgent] 사용 가능한 포트를 찾을 수 없습니다.");

        server.createContext("/status", new StatusHandler());
        server.createContext("/log", new LogHandler());
        server.createContext("/flush", new FlushHandler()); // ← 추가
        server.createContext("/net/fail", new NetFailHandler());
        server.createContext("/event", new EventHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("[SecureAgent] 상태 서버가 " + runningPort + " 포트에서 실행 중입니다.");
    }


    static class StatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    static class LogHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                if ("POST".equals(exchange.getRequestMethod())) {
                    // 테스트용 장애 주입
                    if (isFailingNow()) {
                        if (failMode == FailMode.HTTP_500) {
                            exchange.sendResponseHeaders(500, 0);
                            exchange.getResponseBody().close();
                            return;
                        } else if (failMode == FailMode.TIMEOUT) {
                            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                            // 클라이언트 READ_TIMEOUT(기본 5000ms)보다 오래 기다리므로 HttpLogger 쪽에서 타임아웃 발생
                            // 이후 정상 응답은 보내지 않음(자연스럽게 클라에서 예외)
                            return;
                        } else if (failMode == FailMode.PERCENT) {
                            exchange.sendResponseHeaders(503, 0); // 가끔 실패(서비스 불가)
                            exchange.getResponseBody().close();
                            return;
                        }
                    }

                    InputStream is = exchange.getRequestBody();
                    String encrypted = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String decrypted = CryptoUtil.decrypt(encrypted);
                    System.out.println("[서버 수신 로그] " + decrypted);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                } else {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.getResponseBody().close();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    static class FlushHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                if (!"POST".equals(exchange.getRequestMethod()) && !"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.getResponseBody().close();
                    return;
                }
                Runnable cb = flushCallback;
                if (cb != null) cb.run();
                byte[] ok = "FLUSH_TRIGGERED".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.getResponseBody().close();
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    static class NetFailHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (!"GET".equals(exchange.getRequestMethod()) && !"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.getResponseBody().close();
                    return;
                }
                var query = exchange.getRequestURI().getQuery();
                String mode = getParam(query, "mode", "off").toLowerCase();
                int forSec  = Integer.parseInt(getParam(query, "forSec", "0"));
                int rate    = Integer.parseInt(getParam(query, "rate", "50"));
                int sleep   = Integer.parseInt(getParam(query, "sleepMs", String.valueOf(sleepMs)));

                if ("500".equals(mode))       { failMode = FailMode.HTTP_500; }
                else if ("timeout".equals(mode)) { failMode = FailMode.TIMEOUT; }
                else if ("percent".equals(mode)) { failMode = FailMode.PERCENT; failPercent = Math.max(0, Math.min(100, rate)); }
                else                           { failMode = FailMode.OFF; failPercent = 0; }

                sleepMs = sleep;
                failUntil = (forSec > 0) ? System.currentTimeMillis() + forSec * 1000L : 0L;

                String msg = "mode=" + failMode + ", forSec=" + forSec + ", rate=" + failPercent + ", sleepMs=" + sleepMs;
                byte[] body = msg.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                System.out.println("[StatusServer] net/fail → " + msg);
            } catch (Exception e) { e.printStackTrace(); }
        }

        private static String getParam(String q, String key, String def) {
            if (q == null || q.isBlank()) return def;
            for (String pair : q.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
            return def;
        }
    }

    /** /event: token+encPayload 수신 → 복호화 → HMAC 재계산 검증 */
    static class EventHandler implements HttpHandler {
        private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, 0);
                    exchange.getResponseBody().close();
                    return;
                }

                // 1) 본문 읽기
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // 2) 전송이 암호화됐으면 복호화, 아니라면 그대로 JSON
                String json;
                try {
                    json = CryptoUtil.decrypt(body);
                } catch (Exception ignore) {
                    json = body;
                }

                JsonObject req = GSON.fromJson(json, JsonObject.class);
                String token = opt(req, "token");
                String encPayload = opt(req, "encPayload");

                if (token == null || encPayload == null) {
                    String msg = "[/event] invalid body";
                    System.out.println(msg);
                    exchange.sendResponseHeaders(400, 0);
                    exchange.getResponseBody().close();
                    return;
                }

                // 3) encPayload 복호화 → ForensicPayload JSON
                String payloadJson;
                try {
                    payloadJson = CryptoUtil.decrypt(encPayload);
                } catch (Exception e) {
                    System.out.println("[/event] encPayload decrypt fail: " + e.getMessage());
                    exchange.sendResponseHeaders(400, 0);
                    exchange.getResponseBody().close();
                    return;
                }

                JsonObject p = GSON.fromJson(payloadJson, JsonObject.class);

                // 4) canonical 구성(에이전트와 동일 규칙)
                String canonical = canonicalString(p);

                // 5) 서버 비밀키로 HMAC 재계산 → 앞 12자 비교
                String expected = hmacHex(canonical, 12);

                boolean ok = token.equalsIgnoreCase(expected);
                System.out.println("[/event] verify=" + ok +
                        " token=" + token +
                        " expected=" + expected +
                        " uid=" + opt(p, "uid") +
                        " deviceId=" + opt(p, "deviceId") +
                        " action=" + opt(p, "action") +
                        " ts=" + opt(p, "ts"));

                // DB 저장 없이 200/400만 응답
                if (ok) {
                    exchange.sendResponseHeaders(200, 0);
                } else {
                    exchange.sendResponseHeaders(400, 0);
                }
                exchange.getResponseBody().close();

            } catch (Exception e) {
                e.printStackTrace();
                try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); } catch (Exception ignore) {}
            }
        }

        private static String canonicalString(JsonObject p) {
            // ver|app|uid|deviceId|contentId|action|ts  (PayloadManager와 동일)
            return get(p,"ver") + "|" + get(p,"app") + "|" + get(p,"uid") + "|" + get(p,"deviceId") + "|" +
                    get(p,"contentId") + "|" + get(p,"action") + "|" + get(p,"ts");
        }

        private static String get(JsonObject o, String k) {
            var e = o.get(k);
            return e == null || e.isJsonNull() ? "-" : e.getAsString();
        }
        private static String opt(JsonObject o, String k) { return get(o, k); }

        private static String hmacHex(String canonical, int hexLen) throws Exception {
            String secret = System.getenv().getOrDefault("AIDT_TOKEN_SECRET", "DEV_TOKEN_SECRET");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            int n = Math.max(4, Math.min(hexLen, sb.length()));
            return sb.substring(0, n);
        }
    }
}
