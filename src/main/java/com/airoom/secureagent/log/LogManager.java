package com.airoom.secureagent.log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogManager {

    // 파일 로그 기록

    /*
    * 지금처럼 src와 같은 루트에 있는 log/ 디렉토리 유지하는 게 좋음
실제 배포 시에는 target/classes 나 실행 디렉토리 기준으로 log/ 폴더가 생성되도록 하는 방식으로 관리하면 돼.
    * */

    private static final String LOG_FILE_PATH = "log/capture-log.txt";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String USER_ID = "userId는 추후 로그인된 사용자로 연동 가능. 지금은 테스트용 하드코딩";
    public static String getUserId() {
        return USER_ID;
    }
    public static void writeLog(String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String fullMessage = "[" + timestamp + "] 사용자 " + USER_ID + " - " + message;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE_PATH, true))) {
            writer.write(fullMessage);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[LogManager] 로그 기록 실패: " + e.getMessage());
        }
    }
}
