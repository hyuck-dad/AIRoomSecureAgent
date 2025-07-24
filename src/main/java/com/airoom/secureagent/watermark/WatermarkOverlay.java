package com.airoom.secureagent.watermark;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class WatermarkOverlay {

    private static final List<JWindow> watermarkWindows = new ArrayList<>();

    public static void showOverlay(String watermarkText, float opacity) {
        hideOverlay(); // 중복 방지: 이전 오버레이 제거

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        for (GraphicsDevice screen : screens) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            JWindow window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setBackground(new Color(0, 0, 0, 0)); // 완전 투명 배경
            window.setBounds(bounds);

            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setFont(new Font("Arial", Font.BOLD, 40));

                    // 투명도(float) → int (0~255)
                    int alpha = (int) (opacity * 255);
                    alpha = Math.max(0, Math.min(255, alpha));

                    // 워터마크 테스트 (발표 시 opacity = 0.3f -> 0.01f 로 시연)
                    // 실제 서비스는 0.005f 로 할 것
                    // 개발 과정에서는 그냥 0.0f


                    Color watermarkColor;
//                    if (opacity <= 0.005f) {
//                        watermarkColor = new Color(200, 200, 200, alpha); // 거의 안 보이는 회색
//                    } else if (opacity <= 0.01f) {
//                        watermarkColor = new Color(180, 180, 180, alpha); // 연한 회색
//                    } else if (opacity <= 0.05f) {
//                        watermarkColor = new Color(255, 255, 255, alpha); // 흰색 (발표 시)
//                    } else {
                    watermarkColor = new Color(255, 0, 0, alpha); // 빨간색 (강조용)
//                    }

                    g2d.setColor(watermarkColor);

                    // 대각선 텍스트 반복 출력
                    for (int x = 0; x < getWidth(); x += 300) {
                        for (int y = 0; y < getHeight(); y += 200) {
                            g2d.rotate(Math.toRadians(-30), x, y);
                            g2d.drawString(watermarkText, x, y);
                            g2d.rotate(Math.toRadians(30), x, y); // 원위치
                        }
                    }

                    g2d.dispose();
                }
            };

            panel.setOpaque(false);
            window.setContentPane(panel);
            window.setVisible(true);
            watermarkWindows.add(window);
        }
    }

    public static void hideOverlay() {
        for (JWindow window : watermarkWindows) {
            window.setVisible(false);
            window.dispose();
        }
        watermarkWindows.clear();
    }
}
