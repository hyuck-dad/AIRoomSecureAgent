package com.airoom.secureagent.capture;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.airoom.secureagent.payload.PayloadFactory;
import com.airoom.secureagent.anomaly.AlertSender;

public class CaptureDetector {

    // PrintScreen 감지 인터페이스
    public interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);
        short GetAsyncKeyState(int keyCode);
    }

    public static void startCaptureWatch() {
        new Thread(() -> {
            System.out.println("[SecureAgent] 캡처 감지 스레드 시작됨");

            while (true) {
                try {
                    // 1. PrintScreen 키 감지
//                    if (User32.INSTANCE.GetAsyncKeyState(0x2C) != 0) {
//                        String msg = "[키보드 감지] PrintScreen 키 입력 감지됨";
//                        System.out.println("[SecureAgent] " + msg);
//
//                        LogManager.writeLog(msg);
//                        HttpLogger.sendLog(msg);
//
//                        Thread.sleep(1000); // 중복 감지 방지
//                    }

                    if (User32.INSTANCE.GetAsyncKeyState(0x2C) != 0) {
                        String msg = "[키보드 감지] PrintScreen 키 입력 감지됨";
                        System.out.println("[SecureAgent] " + msg);

                        // 구조화 이벤트 + 라인 로그 동시 발행
                        LogEvent ev = LogEvent.of(
                                EventType.CAPTURE,
                                "PrintScreen",
                                /* pageTitle */ "-",   // TODO: 추후 WindowUtil 연동
                                null,
                                LogManager.getUserId()
                        );
                        LogEmitter.emit(ev, msg);
                        // ★ 서버 실시간 검증 전송 (contentId는 "-" 혹은 포커스 윈도우 타이틀로 교체 가능)
                        AlertSender.sendForensicEvent(PayloadFactory.forEvent(EventType.CAPTURE, "-"));

                        Thread.sleep(1000);
                    }

                    // 2. 캡처/녹화 프로그램 실행 감지
                    ProcessMonitor.detect();

                    Thread.sleep(200); // 감지 주기: 0.2초

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
