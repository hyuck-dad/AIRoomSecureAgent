package com.airoom.secureagent.server;

import com.airoom.secureagent.util.CryptoUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class StatusServer {

    // 내장 서버 및 핸들러

    private static int runningPort = -1;

    public static void startServer() throws Exception {
        int port = 4455;
        HttpServer server = null;

        while (port <= 65535) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                runningPort = port;
                break;
            } catch (BindException e) {
                port++;
            }
        }

        if (server == null) {
            throw new RuntimeException("[SecureAgent] 사용 가능한 포트를 찾을 수 없습니다.");
        }

        server.createContext("/status", new StatusHandler());
        server.createContext("/log", new LogHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("[SecureAgent] 상태 서버가 " + runningPort + " 포트에서 실행 중입니다.");
    }

    public static int getRunningPort() {
        return runningPort;
    }

    static class StatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                String response = "OK";
                // ToDo:
                // 단순 메세지 말고 여기도 가능하면 암호화해서,
                // 암호화된 내용을 받아서 연결을 확인할 수 있도록 해주자
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static class LogHandler implements HttpHandler {
        public void handle(HttpExchange exchange) {
            try {
                if ("POST".equals(exchange.getRequestMethod())) {
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

