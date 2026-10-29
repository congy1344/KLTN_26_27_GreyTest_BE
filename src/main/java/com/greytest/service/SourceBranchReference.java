package com.greytest.service;

/** Giữ liên kết BR với quyết định source theo cùng định dạng review_note của luồng sinh thông thường. */
public final class SourceBranchReference {
    private static final String PREFIX = "SOURCE_BRANCH:";
    private SourceBranchReference() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String id = value.trim();
        int separator = id.indexOf("::");
        return separator < 0 ? id.replaceAll("-(TRUE|FALSE)$", "") : id.substring(0, separator);
    }

    public static String read(String note) {
        if (note == null || !note.startsWith(PREFIX)) return null;
        return normalize(note.substring(PREFIX.length()).split("\n", 2)[0]);
    }

    public static String write(String branch, String note) {
        String id = normalize(branch);
        if (id == null) return note;
        String rest = note == null ? "" : note;
        if (rest.startsWith(PREFIX)) rest = rest.contains("\n") ? rest.substring(rest.indexOf('\n') + 1) : "";
        return PREFIX + id + "\n" + rest;
    }
}
