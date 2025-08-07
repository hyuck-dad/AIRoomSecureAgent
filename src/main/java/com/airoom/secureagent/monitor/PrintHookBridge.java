package com.airoom.secureagent.monitor;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import java.nio.file.Path;

// 추후에 OS 단계 후킹이 필요할때를 위해 남겨놓겠음

/**
 * VeryPDF HookPrinter SDK (또는 유사 DLL) 과 JNA 브리지.
 * DLL 빌드 후 project root에 두거나 java.library.path 설정.
 */
public class PrintHookBridge {

    /** DLL 인터페이스 정의 */
    public interface HookDLL extends Library {
        void StartHook(PrintCallback cb);      // DLL 이 콜백 받을 함수
        void StopHook();
    }

    /** DLL → Java 로 전달되는 콜백 시그니처 */
    public interface PrintCallback extends Callback {
        void invoke(String fullPdfPath);
    }

    private static HookDLL DLL;

    public static void init() {
        if (!Platform.isWindows()) return;

        try {
            DLL = Native.load("hookprinter", HookDLL.class);
            DLL.StartHook((String path) -> {
                System.out.println("[PrintHook] PDF 생성 → " + path);
                StegoDispatcher.process(Path.of(path));
            });
            Runtime.getRuntime().addShutdownHook(new Thread(DLL::StopHook));
            System.out.println("[PrintHook] 활성화 완료");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[PrintHook] DLL 미탑재 → 기능 비활성화");
        }
    }

}
