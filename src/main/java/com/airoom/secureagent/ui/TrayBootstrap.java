package com.airoom.secureagent.ui;

import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.SecureAgentMain;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrayBootstrap {
    private static TrayIcon TRAY;
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    private static final String FRONT_URL = System.getProperty("aidt.frontUrl", "http://43.200.2.244/");
    private static final String BACK_URL  = System.getProperty("aidt.backUrl",  "http://43.200.2.244:8080");

    public static boolean init(String version, String shaShort, String shaFull) {
        try {
            if (!SystemTray.isSupported()) return false;
            System.setProperty("java.awt.headless", "false");

            PopupMenu menu = new PopupMenu();

            // 상태 확인
            MenuItem miStatus = new MenuItem("상태 확인");
            miStatus.addActionListener(showStatusAction());
            menu.add(miStatus);

            menu.addSeparator();

            // 포렌식 페이지
            MenuItem miPortal = new MenuItem("디지털 포렌식 분석기 열기");
            miPortal.addActionListener(e -> {
                try {
                    if (Desktop.isDesktopSupported() &&
                            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new URI(joinUrl(FRONT_URL, "forensic")));
                    } else {
                        notifyWarn("이 환경에서는 브라우저 열기를 지원하지 않습니다.");
                    }
                } catch (Exception ex) {
                    notifyError("페이지 열기 실패: " + ex.getMessage());
                }
            });
            menu.add(miPortal);

            // 최신버전 여부 확인(내부 다이얼로그)
            MenuItem miBackend = new MenuItem("최신 버전 여부 확인");
            // (기존 miBackend.addActionListener(...) 전체를 아래로 교체)
            miBackend.addActionListener(e -> new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String url = BACK_URL + "/api/agent/verify"
                            + "?version=" + URLEncoder.encode(version, StandardCharsets.UTF_8)
                            + "&sha256=" + URLEncoder.encode(shaFull, StandardCharsets.UTF_8);

                    conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(2500);
                    conn.setRequestMethod("GET");

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String body;
                    try (InputStream in = is;
                         java.util.Scanner sc = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
                        body = (sc.hasNext() ? sc.next() : "");
                    }

                    boolean ok = body.contains("\"ok\":true");
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            null,
                            ok ? "최신 버전입니다." : "최신 버전이 아닙니다.",
                            "버전 확인",
                            ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
                    ));
                } catch (Exception ex) {
                    final String msg = "네트워크 등의 문제로 버전 확인에 실패했습니다.\n(" + ex.getClass().getSimpleName() + ")";
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            null, msg, "버전 확인", JOptionPane.ERROR_MESSAGE
                    ));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start());

            menu.add(miBackend);

            menu.addSeparator();

            // 종료
            MenuItem miQuit = new MenuItem("종료");
            miQuit.addActionListener(e -> {
                notifyInfo("종료를 시작하겠습니다.");
                SecureAgentMain.shutdownAndExit();
            });
            menu.add(miQuit);

            // 아이콘 로드 (PNG 우선, 없으면 ICO 폴백)
            Image image = loadIconPreferPng("/app/yellowicon.png", "/app/yellowicon.ico");
            TRAY = new TrayIcon(image, "SecureAgent " + version + "  (" + shaShort + ")", menu);
            TRAY.setImageAutoSize(true);
            TRAY.addActionListener(showStatusAction());

            SystemTray.getSystemTray().add(TRAY);
            READY.set(true);

            notifyInfo("보호 기능이 활성화되었습니다.");
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    // --- 로컬 상태 서버 유틸 ---
    private static String resolveLocalUrl(String path) {
        int p = StatusServer.getRunningPort();
        if (p > 0) {
            String u = "http://127.0.0.1:" + p + path;
            if (ping(u, 500, 700)) return u;
        }
        for (int port = 4455; port <= 4460; port++) {
            String u = "http://127.0.0.1:" + port + path;
            if (ping(u, 500, 700)) return u;
        }
        return null;
    }
    private static boolean ping(String url, int connectMs, int readMs) {
        try {
            var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception ignore) { return false; }
    }

    // --- 아이콘 로더 (PNG 권장) ---
    private static Image loadIconPreferPng(String pngPath, String icoPath) {
        // 1) PNG 우선 (권장: 32x32)
        try {
            var url = TrayBootstrap.class.getResource(pngPath);
            if (url != null) {
                BufferedImage bi = ImageIO.read(url);
                if (bi != null) {
                    // 트레이 크기에 맞춰 스케일(주로 16)
                    int size = SystemTray.getSystemTray().getTrayIconSize().width;
                    if (size > 0 && (bi.getWidth() != size || bi.getHeight() != size)) {
                        Image scaled = bi.getScaledInstance(size, size, Image.SCALE_SMOOTH);
                        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = out.createGraphics();
                        g.drawImage(scaled, 0, 0, null);
                        g.dispose();
                        return out;
                    }
                    return bi;
                }
            }
        } catch (Exception ignore) {}

        // 2) ICO 폴백 (JVM에서 ico 지원이 들쭉날쭉)
        try {
            var url = TrayBootstrap.class.getResource(icoPath);
            if (url != null) return Toolkit.getDefaultToolkit().createImage(url);
        } catch (Exception ignore) {}

        // 3) 최후 폴백: 단색 16x16
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.YELLOW); g.fillRect(0,0,16,16); g.dispose();
        return img;
    }

    private static String joinUrl(String base, String path) {
        if (base.endsWith("/")) base = base.substring(0, base.length()-1);
        if (!path.startsWith("/")) path = "/" + path;
        return base + path;
    }

    private static ActionListener showStatusAction() {
        return e -> new Thread(() -> {
            try {
                String urlStr = resolveLocalUrl("/status");
                if (urlStr == null) { notifyError("상태 서버를 찾을 수 없습니다."); return; }
                var conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                conn.setConnectTimeout(1200);
                conn.setReadTimeout(2000);
                try (var is = conn.getInputStream();
                     var sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    final String body = sc.hasNext() ? sc.next() : "{}";

                    // JSON에서 version, port만 추출 (가벼운 파서)
                    final String version = extractJsonString(body, "version");
                    final Integer port   = extractJsonInt(body, "port");

                    final String msg = String.format("version: %s\nport: %s",
                            (version != null ? version : "-"),
                            (port != null ? port : "-"));

                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(null, msg, "SecureAgent 상태", JOptionPane.INFORMATION_MESSAGE)
                    );
                }
            } catch (Exception ex) {
                notifyError("상태 조회 실패: " + ex.getMessage());
            }
        }).start();
    }


    // --- 알림/툴팁 ---
    public static void notifyInfo(String msg) { if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.INFO); }
    public static void notifyWarn(String msg) { if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.WARNING); }
    public static void notifyError(String msg){ if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.ERROR); }
    public static void updateTooltip(String tooltip) { if (READY.get() && tooltip != null) TRAY.setToolTip(tooltip); }

    public static void remove() {
        if (READY.get()) {
            try { SystemTray.getSystemTray().remove(TRAY); } catch (Throwable ignore) {}
            READY.set(false);
        }
    }

    // 아주 단순한 JSON 값 추출기 (따옴표 포함 문자열)
    private static String extractJsonString(String json, String key) {
        try {
            String pat = "\"" + key + "\"\\s*:\\s*\"";
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return null;
            int colon = json.indexOf(':', k);
            if (colon < 0) return null;
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        } catch (Exception ignore) { return null; }
    }

    // 숫자 값 추출기
    private static Integer extractJsonInt(String json, String key) {
        try {
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return null;
            int colon = json.indexOf(':', k);
            if (colon < 0) return null;
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            int j = i;
            while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
            if (i == j) return null;
            return Integer.parseInt(json.substring(i, j));
        } catch (Exception ignore) { return null; }
    }

}
