package com.greytest.service.generation;

/** Giữ đề xuất AI riêng khi bản nháp đã có quyết định của người dùng. */
public class GenerationConflictException extends RuntimeException {
    private final Long itemId;
    private final String proposal;
    public GenerationConflictException(Long itemId,String proposal) {
        super("Đề xuất xung đột với dữ liệu đã duyệt/chỉnh sửa, item "+itemId);
        this.itemId=itemId; this.proposal=proposal;
    }
    public Long itemId() { return itemId; }
    public String proposal() { return proposal; }
}
