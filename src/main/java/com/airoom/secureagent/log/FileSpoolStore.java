package com.airoom.secureagent.log;

import com.airoom.secureagent.util.CryptoUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class FileSpoolStore implements OfflineLogStore {

    private final Path baseDir;
    private final Path readyDir;
    private final Path sendingDir;
    private final boolean encryptAtRest; // 디스크 저장 시 AES 적용 여부

    // ── 구성: JVM prop이 최우선, 없으면 ENV
    //  -Daidt.spool.encrypt=true  |  AIDT_SPOOL_ENCRYPT=true/1
    private static boolean resolveEncryptAtRest() {
        String p = System.getProperty("aidt.spool.encrypt");
        if (p != null) return p.equalsIgnoreCase("true") || p.equals("1");
        String e = System.getenv("AIDT_SPOOL_ENCRYPT");
        return e != null && (e.equalsIgnoreCase("true") || e.equals("1"));
    }

    public FileSpoolStore() throws IOException {
        this(defaultBaseDir(), resolveEncryptAtRest());
    }

    public FileSpoolStore(Path baseDir, boolean encryptAtRest) throws IOException {
        this.baseDir = (baseDir == null) ? defaultBaseDir() : baseDir;
        this.encryptAtRest = encryptAtRest;
        this.readyDir = this.baseDir.resolve("ready");
        this.sendingDir = this.baseDir.resolve("sending");
        Files.createDirectories(readyDir);
        Files.createDirectories(sendingDir);
    }

    private static Path defaultBaseDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, "SecureAgent", "spool");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".secureagent", "spool");
    }

    private static String newBaseName() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

    private String ext() {
        return encryptAtRest ? ".elog" : ".log";
    }

    @Override
    public synchronized void append(String message) throws IOException {
        String finalBody = encryptAtRest ? safeEncrypt(message) : message;

        String base = newBaseName();
        Path tmp = readyDir.resolve(base + ext() + ".tmp");
        Path target = readyDir.resolve(base + ext());
        Files.writeString(tmp, finalBody, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(tmp, target, ATOMIC_MOVE);
    }

    @Override
    public synchronized List<Path> listReady(int max) throws IOException {
        try (Stream<Path> s = Files.list(readyDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .limit(max)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public synchronized String read(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString().toLowerCase();
        // 파일 확장자로 저장 시점 암호화 여부 감지 (혼재 상황 안전)
        if (name.endsWith(".elog")) {
            try {
                return CryptoUtil.decrypt(raw);
            } catch (Exception e) {
                throw new IOException("decrypt failed: " + e.getMessage(), e);
            }
        }
        return raw;
    }

    @Override
    public synchronized Path markSending(Path readyFile) throws IOException {
        Path dest = sendingDir.resolve(readyFile.getFileName());
        Files.move(readyFile, dest, ATOMIC_MOVE, REPLACE_EXISTING);
        return dest;
    }

    @Override
    public synchronized void markDone(Path sendingFile) throws IOException {
        Files.deleteIfExists(sendingFile);
    }

    @Override
    public synchronized void markFailed(Path sendingFile) throws IOException {
        Path dest = readyDir.resolve(sendingFile.getFileName());
        Files.move(sendingFile, dest, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    @Override
    public synchronized void recoverOrphanedSending() throws IOException {
        try (Stream<Path> s = Files.list(sendingDir)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                markFailed(p); // sending -> ready
            }
        }
    }

    private static String safeEncrypt(String message) throws IOException {
        try {
            return CryptoUtil.encrypt(message); // Base64 문자열
        } catch (Exception e) {
            throw new IOException("encrypt failed: " + e.getMessage(), e);
        }
    }
}
