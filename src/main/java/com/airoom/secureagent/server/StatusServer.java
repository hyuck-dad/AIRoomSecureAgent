package com.airoom.secureagent.server;

import com.airoom.secureagent.util.CryptoUtil;
import com.sun.net.httpserver.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.ThreadLocalRandom;


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

}
