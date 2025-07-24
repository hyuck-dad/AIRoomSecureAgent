package com.airoom.secureagent.capture;

import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ProcessMonitor {

    // 캡처 도구 등 프로세스 감지


    // 마지막 감지 시각을 저장
    private static final Map<String, Long> lastDetectedMap = new HashMap<>();
    private static final long DETECTION_INTERVAL_MS = 10_000; // 10초 간격

    // 감지 대상 캡처 도구, 이거는 나중에 다른 캡처 프로그램 감지까지 감안해서 추가될 수 있음!!!!
    // 지금은 '캡처 도구' 사용할 때에는 다 잡힘! - "SnippingTool.exe"
    // PrintScreen 버튼 눌렀을때에도 "SnippingTool.exe" 가 실행되는 걸로 확인됌.
    private static final String[] suspiciousProcesses = {
            "SnippingTool.exe", "ALCapture.exe", "Snipaste.exe"
    };

    public static void detect() {
        try {
            Process process = Runtime.getRuntime().exec("tasklist");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                for (String proc : suspiciousProcesses) {
                    if (line.contains(proc)) {
                        long now = System.currentTimeMillis();
                        Long lastDetected = lastDetectedMap.get(proc);

                        // 마지막 감지 이후 10초가 지났으면 로그 기록
                        if (lastDetected == null || (now - lastDetected > DETECTION_INTERVAL_MS)) {
                            String logMessage = "[경고] 감지된 캡처 도구 실행 중: " + proc;
                            System.out.println("[서버 수신 로그] " + logMessage);
                            LogManager.writeLog(logMessage);
                            HttpLogger.sendLog(logMessage);

                            lastDetectedMap.put(proc, now);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
