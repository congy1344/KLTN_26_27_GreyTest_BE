package com.greytest.service.storage.workspace;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Đại diện cho một workspace source code (plaintext) phục vụ các tác vụ phân tích,
 * sinh test hoặc chạy sandbox. Hỗ trợ tự động dọn dẹp khi kết thúc qua AutoCloseable.
 */
public class StorageWorkspace implements AutoCloseable {

    private final Path path;
    private final boolean ephemeral;
    private final Consumer<Path> cleanupAction;
    private boolean closed = false;

    public StorageWorkspace(Path path, boolean ephemeral, Consumer<Path> cleanupAction) {
        this.path = path;
        this.ephemeral = ephemeral;
        this.cleanupAction = cleanupAction;
    }

    public Path getPath() {
        return path;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (ephemeral && cleanupAction != null) {
                cleanupAction.accept(path);
            }
        }
    }
}
