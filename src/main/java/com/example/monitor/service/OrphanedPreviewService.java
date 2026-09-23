package com.example.monitor.service;

import com.example.monitor.config.MonitorProperties;
import com.example.monitor.dto.OrphanedCleanupResponse;
import com.example.monitor.repository.RecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.*;

/** 確認したファイルだけを削除し、確認後に増えたファイルを巻き込まないための操作。 */
@Service
@RequiredArgsConstructor
public class OrphanedPreviewService {
    private final MonitorProperties properties;
    private final RecordingRepository repository;
    private final ProcessLauncher processLauncher;
    private final ActiveVideoJobs activeVideoJobs;

    /** 削除候補と除外理由を、ファイルを変更せずに返す。 */
    public Preview preview() {
        Path base = Path.of(properties.recording().directory()).toAbsolutePath().normalize();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> skipped = new LinkedHashSet<>();
        Set<String> known = new HashSet<>();
        repository.findAll().forEach(r -> known.add(r.getVideoId()));
        if (Files.isDirectory(base)) {
            try (var files = Files.walk(base)) {
                for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                    Path relative = base.relativize(file);
                    if (relative.getNameCount() < 2) continue;
                    String videoId = com.example.monitor.util.RecordingPathUtils.videoId(file);
                    String channelId = relative.getName(0).toString();
                    if (known.contains(videoId)) continue;
                    if (activeVideoJobs.isActive(videoId)
                            || processLauncher.isRunningWithCommandLineContaining(videoId)
                            || processLauncher.isRunningWithCommandLineContaining(channelId)) {
                        skipped.add(channelId + "（取得中）");
                        continue;
                    }
                    candidates.add(new Candidate(relative.toString().replace('\\', '/'), videoId,
                            Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                }
            } catch (IOException e) { throw new IllegalStateException("削除候補の確認に失敗しました", e); }
        }
        return new Preview(fingerprint(candidates), candidates, List.copyOf(skipped),
                candidates.stream().mapToLong(Candidate::bytes).sum());
    }

    /** 確認後の変更を検出し、録画開始と同じ予約を取得してからファイル単位で削除する。 */
    public OrphanedCleanupResponse deleteConfirmed(String token) {
        Preview current = preview();
        if (!current.token().equals(token)) throw new IllegalArgumentException("対象が変わりました。削除候補を再確認してください。");
        Path base = Path.of(properties.recording().directory()).toAbsolutePath().normalize();
        int count = 0;
        long freed = 0;
        Set<String> channels = new HashSet<>();
        List<String> skipped = new ArrayList<>(current.skipped());
        for (Candidate candidate : current.files()) {
            String channel = candidate.path().split("/", 2)[0];
            if (!activeVideoJobs.reserve(candidate.videoId())) { skipped.add(candidate.path() + "（取得中）"); continue; }
            try {
                Path file = base.resolve(candidate.path());
                if (repository.existsByVideoId(candidate.videoId())
                        || processLauncher.isRunningWithCommandLineContaining(candidate.videoId())
                        || processLauncher.isRunningWithCommandLineContaining(channel)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) != candidate.bytes()
                        || Files.getLastModifiedTime(file).toMillis() != candidate.modifiedAt()) {
                    skipped.add(candidate.path() + "（状態が変化）"); continue;
                }
                if (Files.deleteIfExists(file)) { count++; freed += candidate.bytes(); channels.add(channel); }
            } catch (IOException e) { skipped.add(candidate.path() + "（削除失敗）"); }
            finally { activeVideoJobs.release(candidate.videoId()); }
        }
        return new OrphanedCleanupResponse(channels.size(), count, freed, skipped);
    }

    private static String fingerprint(List<Candidate> candidates) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(candidates.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** ファイル名だけでなく更新状態も確認し、別の内容への置換を検出する。 */
    public record Candidate(String path, String videoId, long bytes, long modifiedAt) {}
    /** トークンは削除候補の同一性確認用であり、認証の代用ではない。 */
    public record Preview(String token, List<Candidate> files, List<String> skipped, long totalBytes) {}
}
