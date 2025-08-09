package com.airoom.secureagent.device;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
// MachineGuid/BIOS/Board/Disk/CPU 수집
public class WindowsSystemIdReader {

    // --- MachineGuid (레지스트리) ---
    public static Optional<String> readMachineGuid() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.contains("MachineGuid")) {
                        // 예) MachineGuid    REG_SZ    83aeb...fa1c
                        String[] parts = line.split("\\s{2,}");
                        if (parts.length >= 3) return Optional.of(parts[2].trim());
                    }
                }
            }
        } catch (Exception ignore) {}
        return Optional.empty();
    }

    // --- BIOS / BaseBoard / Disk / CPU (PowerShell 우선, 실패 시 WMIC) ---
    public static Optional<String> readBiosSerial() {
        Optional<String> ps = runPowerShell("(Get-CimInstance Win32_BIOS).SerialNumber");
        return ps.isPresent() ? ps : runWmic("bios", "serialnumber");
    }

    public static Optional<String> readBaseboardSerial() {
        Optional<String> ps = runPowerShell("(Get-CimInstance Win32_BaseBoard).SerialNumber");
        return ps.isPresent() ? ps : runWmic("baseboard", "serialnumber");
    }

    public static Optional<String> readDiskSerial() {
        Optional<String> ps = runPowerShell("(Get-CimInstance Win32_PhysicalMedia | Select-Object -First 1).SerialNumber");
        return ps.isPresent() ? ps : runWmic("diskdrive", "serialnumber");
    }

    public static Optional<String> readCpuId() {
        Optional<String> ps = runPowerShell("(Get-CimInstance Win32_Processor | Select-Object -First 1).ProcessorId");
        return ps.isPresent() ? ps : runWmic("cpu", "processorid");
    }

    // ----------------- helpers -----------------
    private static Optional<String> runPowerShell(String expr) {
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", expr)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String out = br.lines().map(String::trim).filter(s -> !s.isEmpty()).reduce((a, b) -> b).orElse(null);
                return cleanup(out);
            }
        } catch (Exception ignore) {}
        return Optional.empty();
    }

    private static Optional<String> runWmic(String cls, String field) {
        try {
            Process p = new ProcessBuilder("wmic", cls, "get", field)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String out = br.lines().map(String::trim).filter(s -> !s.isEmpty()).reduce((a, b) -> b).orElse(null);
                return cleanup(out);
            }
        } catch (Exception ignore) {}
        return Optional.empty();
    }

    private static Optional<String> cleanup(String s) {
        if (s == null) return Optional.empty();
        s = s.trim();
        if (s.isEmpty()) return Optional.empty();
        // 제조사 기본 문자열/NULL 값 간단 필터
        if (s.equalsIgnoreCase("To Be Filled By O.E.M.") || s.equalsIgnoreCase("None") || s.equalsIgnoreCase("Null")) {
            return Optional.empty();
        }
        return Optional.of(s);
    }
}
