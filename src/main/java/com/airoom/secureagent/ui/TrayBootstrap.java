package com.airoom.secureagent.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrayBootstrap {
    private static TrayIcon TRAY;
    private static final AtomicBoolean READY = new AtomicBoolean(false);

    public static boolean init(String version, String shaShort) {
        try {
            if (!SystemTray.isSupported()) return false;

            System.setProperty("java.awt.headless", "false");

            PopupMenu menu = new PopupMenu();

            MenuItem miStatus = new MenuItem("상태 확인");
            miStatus.addActionListener(showStatusAction());
            menu.add(miStatus);

            MenuItem miFlush = new MenuItem("즉시 재전송(/flush)");
            miFlush.addActionListener(e -> {
                try {
                    // 로컬 상태 서버에 flush 트리거 (이미 StatusServer.registerFlushCallback 연결됨)
                    new java.net.URL("http://127.0.0.1:4455/flush").openStream().close();
                    notifyInfo("오프라인 로그 재전송을 시작했습니다.");
                } catch (Exception ex) { notifyError("재전송 실패: " + ex.getMessage()); }
            });
            menu.add(miFlush);

            menu.addSeparator();

            MenuItem miPortal = new MenuItem("대시보드 열기");
            miPortal.addActionListener(e -> {
                try { Desktop.getDesktop().browse(new URI("http://43.200.2.244/")); }
                catch (Exception ignored) {}
            });
            menu.add(miPortal);

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
        return e -> {
            try {
                var s = new java.util.Scanner(new java.net.URL("http://127.0.0.1:4455/status").openStream(), "UTF-8")
                        .useDelimiter("\\A").next();
                JOptionPane.showMessageDialog(null, s, "SecureAgent 상태", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                notifyError("상태 조회 실패: " + ex.getMessage());
            }
        };
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
            SystemTray.getSystemTray().remove(TRAY);
            READY.set(false);
        }
    }
}
