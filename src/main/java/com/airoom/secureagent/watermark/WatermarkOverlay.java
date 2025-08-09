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
            // 포커스를 빼서 사용자 입력 방해 최소화 (클릭 스루까지는 아님)
            window.setFocusableWindowState(false);

            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2d = (Graphics2D) g.create();

                    // ----[추가] 글자 렌더링 품질 개선 ----
                    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);

                    // 기존 폰트 설정은 유지하되, 화면 크기에 비례해 스케일
                    // g2d.setFont(new Font("Arial", Font.BOLD, 40));
                    int w = getWidth(), h = getHeight();
                    int base = Math.max(40, Math.min(w, h) / 20); // 화면 크기 기반 동적 폰트
                    g2d.setFont(new Font("Arial", Font.BOLD, base));

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

                    // ----[추가] 간격도 해상도 비례 조정 ----
                    int stepX = Math.max(200, w / 5);
                    int stepY = Math.max(140, h / 5);

                    // 대각선 텍스트 반복 출력
                    for (int x = 0; x < w; x += stepX) {
                        for (int y = 0; y < h; y += stepY) {
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
