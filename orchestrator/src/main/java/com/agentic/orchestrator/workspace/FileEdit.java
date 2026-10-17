package com.agentic.orchestrator.workspace;

/**
 * One change an agent asks for. Agents never write files or run git themselves; they return edits,
 * the tool layer checks them against the agent's permissions, applies them and commits a real diff.
 *
 * <ul>
 *   <li>{@code create}: new file with {@code content}; fails if the file exists</li>
 *   <li>{@code replace}: overwrite an existing file with {@code content}</li>
 *   <li>{@code patch}: replace the single occurrence of {@code search} with {@code replace}</li>
 *   <li>{@code delete}: remove an existing file</li>
 * </ul>
 */
public record FileEdit(String path, String action, String content, String search, String replace) {
    public static FileEdit create(String path, String content) {
        return new FileEdit(path, "create", content, null, null);
    }

    public static FileEdit replaceFile(String path, String content) {
        return new FileEdit(path, "replace", content, null, null);
    }

    public static FileEdit patch(String path, String search, String replace) {
        return new FileEdit(path, "patch", null, search, replace);
    }

    public static FileEdit delete(String path) {
        return new FileEdit(path, "delete", null, null, null);
    }
}
