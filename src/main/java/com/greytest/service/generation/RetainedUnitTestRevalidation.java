package com.greytest.service.generation;

import java.nio.file.Path;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.entity.Project;
import com.greytest.entity.SourceRevision;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

import lombok.extern.slf4j.Slf4j;

/** Đối chiếu các test được giữ lại ngay sau khi revision mới thành active. */
@Service
@Slf4j
public class RetainedUnitTestRevalidation {
    public record SourceApplied(Project project, Long updateId, Long baseId, SourceRevision candidate, Set<Long> writtenTests) {}

    private final UnitTestRepository tests;
    private final SourceRevisionRepository revisions;
    private final ProjectRepository projects;
    private final StorageWorkspaceService workspaces;
    private final UnitTestExportProvenanceService provenance;
    private final TransactionTemplate transaction;

    public RetainedUnitTestRevalidation(UnitTestRepository tests, SourceRevisionRepository revisions,
            ProjectRepository projects, StorageWorkspaceService workspaces, UnitTestExportProvenanceService provenance,
            PlatformTransactionManager manager) {
        this.tests = tests;
        this.revisions = revisions;
        this.projects = projects;
        this.workspaces = workspaces;
        this.provenance = provenance;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Mỗi test có giao dịch riêng để cả lỗi validator lẫn lỗi flush chỉ bỏ qua một test. */
    @TransactionalEventListener
    public void afterApply(SourceApplied event) {
        Project project = event.project();
        Long projectId = project.getId();
        try {
            var ids = tests.findStampedIdsByProjectId(projectId).stream()
                    .filter(id -> !event.writtenTests().contains(id)).toList();
            if (ids.isEmpty()) return;
            var base = revisions.findById(event.baseId()).orElseThrow();
            var active = event.candidate();
            try (var previous = workspaces.acquireDirectoryWorkspace(project, Path.of(base.getStoragePath()),
                         Boolean.TRUE.equals(base.getStorageEncrypted()), false);
                 var current = workspaces.acquireDirectoryWorkspace(project, Path.of(active.getStoragePath()),
                         Boolean.TRUE.equals(active.getStorageEncrypted()))) {
                Path baseRoot = logicalRoot(base, previous.getPath());
                Path activeRoot = logicalRoot(active, current.getPath());
                provenance.inExportScope(() -> {
                    for (Long id : ids) {
                        try {
                            transaction.executeWithoutResult(status -> {
                                // Nếu project đã chuyển sang revision khác, dấu này không còn giá trị.
                                var locked = projects.findByIdForUpdate(projectId);
                                if (locked.isEmpty() || !active.getId().equals(locked.get().getActiveSourceRevisionId())) return;
                                tests.findByIdForUpdate(id).ifPresent(test ->
                                        provenance.revalidateRetained(projectId, event.updateId(), test, baseRoot, activeRoot,
                                                current.getPath()));
                            });
                        } catch (RuntimeException failure) {
                            log.warn("Bỏ qua xác minh Unit Test {} của update {}", id, event.updateId(), failure);
                        }
                    }
                    return null;
                });
            }
        } catch (RuntimeException failure) {
            log.warn("Không thể xác minh các Unit Test của update {}", event.updateId(), failure);
        }
    }

    /** Giữ đường dẫn tương đối của logical root khi revision được giải mã sang thư mục tạm. */
    private Path logicalRoot(SourceRevision revision, Path workspace) {
        if (revision.getLogicalRoot() == null) return workspace;
        Path storage = Path.of(revision.getStoragePath()).toAbsolutePath().normalize();
        Path logical = Path.of(revision.getLogicalRoot()).toAbsolutePath().normalize();
        if (!logical.startsWith(storage)) throw new IllegalArgumentException("Invalid revision root");
        return workspace.resolve(storage.relativize(logical));
    }
}
