// com.airoom.secureagent.util.SingleInstance.java
package com.airoom.secureagent.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

import static java.nio.file.StandardOpenOption.*;
/*
 실행 시 단일 인스턴스 보장(FileLock)
  FileLock이 뭔데? 왜 써?
목적
중복 실행 방지: 사용자가 더블클릭, + 자동 시작(HKCU Run/스케줄러)로 두 개가 뜨는 사고 차단.
파일 일관성: 스풀 폴더(ready/sending)에 두 프로세스가 동시에 접근하면 레이스/유실 위험.
업데이트/언인스톨 안정성: 실행 중 덮어쓰기/삭제 방지 힌트.
어떻게 동작?
FileChannel#tryLock()은 OS 커널 레벨 배타 잠금을 요청.
성공하면 “내가 첫 인스턴스”.
실패면 “이미 누가 쓰는 중” → 즉시 종료.
Windows에선 NIO 파일락이 강제(exclusive)라, 다른 프로세스도 그 파일에 같은 식 잠금을 못 건다.
프로세스가 죽으면 OS가 핸들을 닫아 락도 해제돼서 “고아 락”은 거의 없다.
*/
public final class SingleInstance {
    private static FileChannel channel;
    private static FileLock lock;

    public static void acquireOrExit(Path lockFile) {
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile, CREATE, WRITE);
            lock = channel.tryLock(); // 이미 잠겨 있으면 null 또는 예외
            if (lock == null) {
                System.err.println("SecureAgent already running. Exit.");
                System.exit(0);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::releaseQuietly));
        } catch (Exception e) {
            System.err.println("Cannot acquire lock: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void releaseQuietly() {
        try { if (lock != null) lock.release(); } catch (IOException ignore) {}
        try { if (channel != null) channel.close(); } catch (IOException ignore) {}
    }
}
