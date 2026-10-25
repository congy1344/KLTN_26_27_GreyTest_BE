package com.greytest.service.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.SourceRevision;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.SnapshotAnalysisLoader.ParsedSnapshot;

/** Lưu analysis source bất biến theo digest/version, tách biệt identity và artifact trong CSDL. */
@Service
public class PersistentSourceAnalysisService {
    private static final int FORMAT_VERSION = 1;
    private final SourceRevisionRepository revisions;
    private final ObjectMapper json;

    public PersistentSourceAnalysisService(SourceRevisionRepository revisions, ObjectMapper json) {
        this.revisions = revisions;
        this.json = json;
    }

    public record StoredSnapshot(int formatVersion, String sourceDigest, String payloadDigest, ParsedSnapshot parsed) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ParsedSnapshot load(Long projectId, Path root, boolean strict, String digest) {
        SourceRevision revision = revisions.findFirstByProjectIdAndStoragePath(projectId,root.toString()).orElseGet(()->revisions.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(candidate -> candidate.getStoragePath() != null)
                .filter(candidate -> Path.of(candidate.getStoragePath()).toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize()))
                .findFirst().orElse(null));
        if (revision != null && revision.getAnalysisSnapshot() != null) {
            try {
                StoredSnapshot stored = json.readValue(revision.getAnalysisSnapshot(), StoredSnapshot.class);
                if (stored.formatVersion() == FORMAT_VERSION && Objects.equals(stored.sourceDigest(), digest)
                        && stored.parsed() != null && stored.parsed().failedParseFiles().isEmpty()
                        && Objects.equals(stored.payloadDigest(), hash(json.writeValueAsBytes(stored.parsed())))) {
                    GenerationJobContext.increment("analysisPersistentHits");
                    return stored.parsed();
                }
            } catch (Exception ignored) {
                // Cache cũ/hỏng không được bỏ qua bước parse hoặc trở thành bằng chứng source hợp lệ.
            }
        }
        ParsedSnapshot parsed = SnapshotAnalysisLoader.parse(root, strict);
        if(!Objects.equals(digest,contentDigest(root))) throw new LlmResponseException("Source snapshot changed while parsing; retry analysis");
        if (revision != null && parsed.failedParseFiles().isEmpty()
                && !Boolean.TRUE.equals(GenerationJobContext.scopedValue("greytest.export-read-only"))) {
            try {
                revision.setAnalysisSnapshot(json.writeValueAsString(new StoredSnapshot(FORMAT_VERSION, digest,
                        hash(json.writeValueAsBytes(parsed)), parsed)));
                revisions.save(revision);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("Cannot persist source analysis", exception);
            }
        }
        return parsed;
    }

    /** Lưu snapshot đã parse trong Analyze để stage đầu tiên không quét lại cùng candidate. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void rememberScan(Path root,com.greytest.service.analysis.JavaParserHelper.SourceScanResult scan,String beforeDigest) {
        if(!Objects.equals(beforeDigest,contentDigest(root))) throw new LlmResponseException("Source snapshot changed while analyzing; retry analysis");
        var matching=revisions.findByStoragePath(root.toString());
        if(matching.size()!=1) return;
        var parsed=SnapshotAnalysisLoader.fromScan(scan,true);
        try {
            var revision=matching.get(0);
            revision.setAnalysisSnapshot(json.writeValueAsString(new StoredSnapshot(FORMAT_VERSION,beforeDigest,
                hash(json.writeValueAsBytes(parsed)),parsed)));
            revisions.save(revision);
            GenerationJobContext.increment("analysisDiffReused");
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot preserve analyzed source snapshot",invalid);
        }
    }

    /** Đọc/hash riêng với parse AST để phát hiện snapshot bị sửa giữa các request hoặc batch. */
    public static String contentDigest(Path root) {
        if (root == null || !Files.isDirectory(root)) throw new LlmResponseException("Source snapshot is missing.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted(Comparator.comparing(value -> root.relativize(value).toString())).toList()) {
                    byte[] name = root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                    byte[] contents = Files.readAllBytes(path);
                    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
                    digest.update(name);
                    digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(contents.length).array());
                    digest.update(contents);
                    GenerationJobContext.increment("analysisHashFiles");
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
            throw new LlmResponseException("Cannot verify source snapshot", exception);
        }
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
