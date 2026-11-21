package com.aipilot.core.model;

public record FileEditProposal(String path, String oldContent, String newContent, String op, String toPath) {
    public FileEditProposal(String path, String oldContent, String newContent) {
        this(path, oldContent, newContent, "write", "");
    }

    public boolean write() {
        return op == null || op.isBlank() || "write".equalsIgnoreCase(op);
    }

    public boolean delete() {
        return "delete".equalsIgnoreCase(op);
    }

    public boolean rename() {
        return "rename".equalsIgnoreCase(op);
    }
}
