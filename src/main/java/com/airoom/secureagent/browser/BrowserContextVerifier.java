package com.airoom.secureagent.browser;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

public class BrowserContextVerifier {

    // 타겟 도메인 (나중에 config로 분리 가능)
    private static final String TARGET_DOMAIN_KEYWORD = "naver";

    public static boolean isTargetBrowserActive() {
        char[] buffer = new char[1024];
        WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        User32.INSTANCE.GetWindowText(hwnd, buffer, 1024);
        String title = Native.toString(buffer);

        // [DEBUG] 현재 활성 윈도우 제목 출력
        System.out.println("[DEBUG] 활성 창 제목: " + title);

        if (title == null || title.isEmpty()) return false;

        // 브라우저 확인 (크롬, 엣지, 파이어폭스 등 대중적인 브라우저 명 포함 여부)
        String lower = title.toLowerCase();
        boolean isBrowser = lower.contains("chrome") || lower.contains("edge") ||
                lower.contains("firefox") || lower.contains("safari") ||
                lower.contains("opera");

        boolean containsTarget = lower.contains(TARGET_DOMAIN_KEYWORD);

        return isBrowser && containsTarget;
    }
}
