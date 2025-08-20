package com.airoom.secureagent.browser;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import java.util.Arrays;

public class BrowserContextVerifier {

    // 타겟 도메인 (나중에 config로 분리 가능)
    // 추후에, 우리팀이 개발한 사이트가 어떤 title 값으로 나타나는지 파악해야겠다.
    private static final String[] TARGET_KEYWORDS = {"Vite"};
    private static final boolean DEBUG = Boolean.getBoolean("aidt.debug");
    private static volatile boolean lastFound = false;
    private static volatile long lastLogMs = 0L;

    public static boolean isTargetBrowserOpenAnywhere() {
        final boolean[] targetFound = {false};

        User32.INSTANCE.EnumWindows((hwnd, pointer) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;

            char[] windowText = new char[1024];
            User32.INSTANCE.GetWindowText(hwnd, windowText, 1024);
            String title = Native.toString(windowText).toLowerCase();

            if (title.isEmpty()) return true;

            boolean isBrowser = title.contains("chrome") || title.contains("edge") ||
                    title.contains("firefox") || title.contains("opera");

            boolean containsTarget = Arrays.stream(TARGET_KEYWORDS)
                    .anyMatch(keyword -> title.contains(keyword.toLowerCase()));

            if (isBrowser && containsTarget) {
//                System.out.println("[DEBUG] 감지된 브라우저 창 타이틀: " + title);
                targetFound[0] = true;
                return false; // 더 이상 검사하지 않고 중단
            }

            return true; // 다음 창으로 계속 검사
        }, null);

        if (DEBUG) {
            boolean now = targetFound[0];
            long nowMs = System.currentTimeMillis();
            if (now != lastFound || nowMs - lastLogMs > 5000) {
                System.out.println("[DEBUG] 브라우저 컨텍스트: " + (now ? "FOUND" : "NOT FOUND"));
                lastFound = now; lastLogMs = nowMs;
            }
        }

        return targetFound[0];
    }
}
