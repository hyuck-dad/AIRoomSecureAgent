package com.airoom.secureagent.ui;

import com.airoom.secureagent.server.StatusServer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.net.URI;
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

            MenuItem miStatus = new MenuItem("상태 확인");
            miStatus.addActionListener(showStatusAction());
            menu.add(miStatus);

            MenuItem miFlush = new MenuItem("즉시 재전송(/flush)");
            miFlush.addActionListener(e -> new Thread(() -> {
                try {
                    String urlStr = resolveLocalUrl("/flush");
                    if (urlStr == null) { notifyError("로컬 상태 서버를 찾을 수 없습니다."); return; }
                    var conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(1200);
                    conn.setReadTimeout(2000);
                    try (var is = conn.getInputStream()) {}
                    notifyInfo("오프라인 로그 재전송을 시작했습니다.");
                } catch (Exception ex) {
                    notifyError("재전송 실패: " + ex.getMessage());
                }
            }).start());
            menu.add(miFlush);

            menu.addSeparator();

            MenuItem miPortal = new MenuItem("디지털 포렌식 분석기 열기");
            miPortal.addActionListener(e -> {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new URI(FRONT_URL + "forensic"));
                    } else {
                        notifyWarn("이 환경에서는 브라우저 열기를 지원하지 않습니다.");
                    }
                } catch (Exception ex) {
                    notifyError("페이지 열기 실패: " + ex.getMessage());
                }
            });
            menu.add(miPortal);

            menu.addSeparator();

            MenuItem miBackend = new MenuItem("최신 버전 여부 확인");
            miBackend.addActionListener(e -> {
                try {
                    if (!Desktop.isDesktopSupported()) {
                        notifyWarn("이 환경에서는 브라우저 열기를 지원하지 않습니다.");
                        return;
                    }
                    // /api/agent/verify (GET) 로 현재 버전/sha256 전달
                    String url = BACK_URL + "/api/agent/verify"
                            + "?version=" + java.net.URLEncoder.encode(version, "UTF-8")
                            + "&sha256=" + java.net.URLEncoder.encode(shaFull, "UTF-8");
                    Desktop.getDesktop().browse(new URI(url));
                } catch (Exception ex) {
                    notifyError("최신 버전 여부 확인 실패: " + ex.getMessage());
                }
            });
            menu.add(miBackend);

            menu.addSeparator();

            MenuItem miQuit = new MenuItem("종료");
            miQuit.addActionListener(e -> {
                notifyInfo("SecureAgent를 종료합니다.");
                System.exit(0);
            });
            menu.add(miQuit);

            Image image = loadIcon("/app/yellowicon.png", "/app/yellowicon.ico");
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

    private static String resolveLocalUrl(String path) {
        // 1) StatusServer가 이미 포트를 알고 있으면 그걸 최우선 사용
        int p = StatusServer.getRunningPort();
        if (p > 0) {
            String u = "http://127.0.0.1:" + p + path;
            if (ping(u, 500, 700)) return u;
        }
        // 2) 아직 -1이면(미기동/초기화 순서 차이) 범위 스캔 폴백
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


    private static Image loadIcon(String... paths) {
        for (String p : paths) {
            try {
                var url = TrayBootstrap.class.getResource(p);
                if (url != null) return Toolkit.getDefaultToolkit().createImage(url);
            } catch (Exception ignored) {}
        }
        // 폴백: 단색 16x16
        Image img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor(Color.YELLOW); g.fillRect(0,0,16,16); g.dispose();
        return img;
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
                    final String s = sc.hasNext() ? sc.next() : "{}";
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(null, s, "SecureAgent 상태", JOptionPane.INFORMATION_MESSAGE)
                    );
                }
            } catch (Exception ex) {
                notifyError("상태 조회 실패: " + ex.getMessage());
            }
        }).start();
    }


    public static void notifyInfo(String msg) {
        if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.INFO);
    }
    public static void notifyWarn(String msg) {
        if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.WARNING);
    }
    public static void notifyError(String msg) {
        if (READY.get()) TRAY.displayMessage("SecureAgent", msg, TrayIcon.MessageType.ERROR);
    }

    public static void updateTooltip(String tooltip) {
        if (READY.get() && tooltip != null) TRAY.setToolTip(tooltip);
    }

    public static void remove() {
        if (READY.get()) {
            try { SystemTray.getSystemTray().remove(TRAY); } catch (Throwable ignore) {}
            READY.set(false);
        }
    }

}
