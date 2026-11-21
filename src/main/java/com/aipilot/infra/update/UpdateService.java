package com.aipilot.infra.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Check / download AIPilot updates from site manifest. */
public final class UpdateService {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private UpdateService() {
    }

    public record ReleaseInfo(
            String version,
            String buildHash,
            String installerUrl,
            long installerSize,
            String installerSha256,
            String windowsZipUrl,
            String windowsZipSha256
    ) {
    }

    public static ReleaseInfo fetchRelease(HttpClient http, String base) throws Exception {
        String[] paths = {"/api/bunker/client", "/api/app/client-release"};
        Exception last = null;
        for (String path : paths) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return parseManifest(resp.body(), base);
                }
                last = new IllegalStateException(path + " HTTP " + resp.statusCode());
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("Manifest unavailable");
    }

    public static ReleaseInfo parseManifest(String body, String base) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.has("data") ? root.get("data") : root;
        String version = text(data, "aipilot_version");
        String hash = text(data, "aipilot_build_hash");
        JsonNode builds = data.get("builds");
        if (builds != null && builds.has("aipilot")) {
            JsonNode a = builds.get("aipilot");
            if (version == null) {
                version = text(a, "version");
            }
            if (hash == null) {
                hash = text(a, "hash");
            }
        }
        String inst = text(data, "aipilot_installer_url");
        if (inst != null && inst.startsWith("/")) {
            inst = base + inst;
        }
        String zip = text(data, "aipilot_windows_url");
        if (zip != null && zip.startsWith("/")) {
            zip = base + zip;
        }
        return new ReleaseInfo(
                version,
                hash,
                inst,
                longVal(data, "aipilot_installer_size"),
                text(data, "aipilot_installer_sha256"),
                zip,
                text(data, "aipilot_windows_sha256")
        );
    }

    public static void download(HttpClient http, String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Download HTTP " + resp.statusCode());
        }
        Files.createDirectories(dest.getParent());
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    public static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static boolean isNewerHash(String remote, String local) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        if (local == null || local.isBlank()) {
            return false;
        }
        return !remote.equalsIgnoreCase(local);
    }

    /** Compare semver-like strings; returns negative if a<b, zero if equal, positive if a>b. */
    public static int compareVersions(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank() ? 0 : -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        String[] pa = a.trim().split("[^0-9]+");
        String[] pb = b.trim().split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length && !pa[i].isBlank() ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isBlank() ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    public static boolean isNewerVersion(String remote, String local) {
        return compareVersions(remote, local) > 0;
    }

    public static boolean isSameVersion(String remote, String local) {
        return compareVersions(remote, local) == 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(JsonNode o, String key) {
        JsonNode n = o.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        String v = n.asText("").trim();
        return v.isEmpty() ? null : v;
    }

    private static long longVal(JsonNode o, String key) {
        JsonNode n = o.get(key);
        return n != null && n.isNumber() ? n.asLong() : 0;
    }
}
