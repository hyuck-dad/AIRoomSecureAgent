package com.airoom.secureagent.server;

import com.airoom.secureagent.util.CryptoUtil;
import com.sun.net.httpserver.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
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

    private static volatile String agentVersion = "1.0.0-dev";
    private static volatile String agentSha256  = "DEV-SHA256-PLACEHOLDER";
    private static volatile String startedAt    = null;
    private static volatile boolean watermarkActive = false;
    private static final SystemInfo SI = new SystemInfo();
    private static volatile long[] prevTicks = null;

    // 설정 진입점
    public static void configure(String version, String sha256){
        if (version != null && !version.isBlank()) agentVersion = version;
        if (sha256  != null && !sha256.isBlank())  agentSha256  = sha256;
    }

    // 공통 응답 유틸
    private static void addCORS(HttpExchange ex){
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin","*");
        h.add("Access-Control-Allow-Headers","*");
        h.add("Access-Control-Allow-Methods","GET,POST,OPTIONS");
    }
    private static void sendJson(HttpExchange ex, int code, String json) throws Exception {
        addCORS(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close(); return;
        }
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    public static void startServer() throws Exception {
        int start = 4455, end = 4460;
        HttpServer server = null;
        for (int port = start; port <= end; port++) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                runningPort = port;
                break;
            } catch (BindException e) { /* 다음 포트 시도 */ }
        }
        if (server == null) throw new RuntimeException("[SecureAgent] 4455~4460 포트를 모두 사용할 수 없습니다.");

        // 시작시각 기록
        startedAt = Instant.now().toString();

        server.createContext("/status", new StatusHandler());
        server.createContext("/log", new LogHandler());
        server.createContext("/flush", new FlushHandler()); // ← 추가
        server.createContext("/net/fail", new NetFailHandler());
        server.createContext("/event", new EventHandler());
        server.createContext("/activate-watermark", new ActivateHandler());
        server.createContext("/download-tag", new DownloadTagHandler());
        server.createContext("/metrics", new MetricsHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("[SecureAgent] 상태 서버가 " + runningPort + " 포트에서 실행 중입니다. ver=" + agentVersion + " sha=" + agentSha256);
    }


    static class StatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                String json = String.format(
                        "{\"version\":\"%s\",\"sha256\":\"%s\",\"startedAt\":\"%s\",\"heartbeatId\":\"%d\",\"port\":%d}",
                        agentVersion, agentSha256, startedAt, System.currentTimeMillis(), runningPort);
                sendJson(exchange, 200, json);
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

    /*
    어떻게 추적하나? (토큰을 DB에 안 저장할 때)
    스크린샷/촬영물에서 토큰과 대략 시각만 확보돼도,
    서버의 이벤트 로그(저장된 encPayload 복호화 로그 or 서버 stdout/수집된 파일 등)에서 그 시간대의 이벤트들을 모으고
    각각의 페이로드로 canonical → HMAC 토큰을 재계산하면
    토큰 매칭되는 이벤트를 찾을 수 있어.
    즉, 토큰 컬럼 인덱스 없이도 시간 범위를 좁혀 재계산으로 역추적이 가능해(좀 수고스럽지만 가능).
    */
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


    // 워터마크 on/off
    static class ActivateHandler implements HttpHandler {
        public void handle(HttpExchange ex) {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) && !"OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    addCORS(ex); ex.sendResponseHeaders(405, 0); ex.getResponseBody().close(); return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean active = body.contains("\"active\":true");
                watermarkActive = active;
                System.out.println("[StatusServer] watermarkActive=" + active + " body=" + body);
                // TODO: 실제 워터마크 토글 호출 지점 연결(현재는 로깅만)
                sendJson(ex, 200, "{\"ok\":true}");
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // 다운로드 태깅(스테가 삽입 후 메타 수신)
    static class DownloadTagHandler implements HttpHandler {
        public void handle(HttpExchange ex) {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) && !"OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    addCORS(ex); ex.sendResponseHeaders(405, 0); ex.getResponseBody().close(); return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("[download-tag] " + body);
                // TODO: 필요한 경우 파일명 해시/사이즈 등 파싱해 별도 로컬 로그/큐에 저장
                sendJson(ex, 200, "{\"ok\":true}");
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // CPU/메모리 노출
    static class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) {
            try {
                CentralProcessor cpu = SI.getHardware().getProcessor();

                if (prevTicks == null) {
                    // 최초 호출: 기준 ticks 확보 후 짧게 대기
                    prevTicks = cpu.getSystemCpuLoadTicks();
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                long[] cur = cpu.getSystemCpuLoadTicks();
                double load = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
                prevTicks = cur;

                long total = SI.getHardware().getMemory().getTotal();
                long avail = SI.getHardware().getMemory().getAvailable();
                long used  = total - avail;

                String json = String.format(java.util.Locale.ROOT,
                        "{\"cpu\":%.2f,\"memUsed\":%d,\"memTotal\":%d,\"ts\":%d}",
                        load, used, total, System.currentTimeMillis());
                sendJson(ex, 200, json);
            } catch (Exception e) {
                e.printStackTrace();
                try { sendJson(ex, 500, "{\"ok\":false}"); } catch (Exception ignore) {}
            }
        }
    }



}
