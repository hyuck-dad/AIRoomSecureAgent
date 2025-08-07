package com.airoom.secureagent.monitor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

// C:\Users\ASUS ROG\AppData\Local\Application Data 는 Windows XP 호환용 junction(심볼릭 링크)
// 일반 권한으로 열려고 하면 Access Denied 가 나므로 Files.walk() 도중 예외가 발생
// → StatusServer.startServer() 까지 도달하지 못하고 바로 종료되었음.
// 안전하게 “읽을 수 있는 폴더만” 재귀 등록하려면,
// java.nio.file.FileVisitor 로 바꿔서 접근 불가 폴더를 건너뛰고 계속 진행할 수 있음
// SecurityException · AccessDeniedException 이 발생하면 로그만 남기고 그 하위 폴더는 건너뜀.
// 홈 디렉터리 내 junction(Application Data, Local Settings 등) · 권한 제한 폴더(AppData\Local\Packages\…)도 자동 패스
// 변경 포인트
// - registerRecursiveSafe()
// Files.walkFileTree + SimpleFileVisitor 사용 →
// AccessDeniedException, SecurityException 발생 시 해당 디렉터리와 하위 전체를 SKIP.
// - 로그
//건너뛴 폴더는 [Watcher] SKIP (권한 없음): … 으로 한 줄만 남기고 계속 실행.
//나머지 로직은 이전 버전과 동일하므로 StegoDispatcher·AlreadyTaggedChecker 연동 그대로 작동함

// walkFileTree 가 심볼릭 링크·Junction 같은 특수 폴더를 만나면
// preVisitDirectory 이전에 visitFileFailed 이 호출됩니다.
// 우리가 visitFileFailed 를 오버라이드하지 않으면 예외가 상위로 전파되어
// SecureAgentMain 의 try-블록에서 곧바로 잡혀
// visitFileFailed 에서 SKIP_SUBTREE 로 돌려 예외를 삼키고 계속 진행
// 링크 루프 방지를 위해 FOLLOW_LINKS 옵션 사용 안 함(기본)


// 일단, 현재는 SKIP 로그는 주석처리를 해놓았고,
// 운영 모드도 전체 범위가 아니라 일부로 제한해서 테스트 모드로 진행하려고 함.

public class GlobalWatcher implements Runnable {

    private final WatchService ws = FileSystems.getDefault().newWatchService();
    private final List<Path> roots;

    /** 테스트 / 운영 모두 이 생성자를 사용 */
    public GlobalWatcher(List<Path> roots) throws IOException {
        this.roots = roots;
        for (Path r : roots) registerRecursiveSafe(r);

        System.out.println("[Watcher] ready");
    }

    /* 메인 루프 */
    @Override public void run() {
        try {
            while (true) {
                WatchKey key = ws.poll(10, TimeUnit.SECONDS);
                if (key == null) continue;

                Path base = (Path) key.watchable();
                for (WatchEvent<?> ev : key.pollEvents()) {
                    Path f = base.resolve((Path) ev.context());
                    if (isTarget(f) && waitUntilStable(f) && !AlreadyTaggedChecker.isTagged(f)) {
                        StegoDispatcher.process(f);
                    }
                }
                key.reset();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /* ---------- util ---------- */

    /** 접근 가능한 디렉터리만 재귀 등록, 실패 폴더는 건너뜀 */
    private void registerRecursiveSafe(Path start) throws IOException {
        if (!Files.isDirectory(start)) return;

        Files.walkFileTree(start, new SimpleFileVisitor<>() {

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY); // <- 동일한 이름으로 덮어쓰기 형식으로 새로운 파일을 다운로드할 수도 있으니 MODIFY도 감지해야함!
                } catch (IOException | SecurityException ex) {
//                    System.err.println("[Watcher] SKIP (권한 없음): " + dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            /** 권한·루프·I/O 문제 등으로 디렉터리 접근 실패 시 건너뛴다 */
            @Override public FileVisitResult visitFileFailed(Path dir, IOException exc) {
//                System.err.println("[Watcher] SKIP (visit failed): " + dir);
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
    }

    private boolean isTarget(Path f) {
        String n = f.toString().toLowerCase();
        if (n.endsWith(".crdownload")) return false;
        return n.endsWith(".pdf") || n.endsWith(".png") || n.matches(".*\\.(jpe?g)$");
    }

    /** 2 초간 크기 변하지 않으면 저장 완료 */
    private boolean waitUntilStable(Path f) {
        try {
            long prev = -1;
            for (int i = 0; i < 30; i++) {
                long cur = Files.size(f);
                if (cur > 0 && cur == prev) return true;
                prev = cur; Thread.sleep(2000);
            }
        } catch (Exception ignore) {}
        return false;
    }
}


