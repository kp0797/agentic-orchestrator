package com.agentic.orchestrator.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates every edit first and applies none if any is invalid, so a half-applied change never
 * reaches a gate. Refused edits are reported back to the agent as feedback.
 */
public final class EditApplier {
    private EditApplier() {}

    public static final class EditRejectedException extends RuntimeException {
        private final List<String> problems;
        private final boolean permissionDenied;

        EditRejectedException(List<String> problems, boolean permissionDenied) {
            super((permissionDenied ? "edits refused by tool permissions: " : "invalid edits: ")
                    + String.join("; ", problems));
            this.problems = List.copyOf(problems);
            this.permissionDenied = permissionDenied;
        }

        public List<String> problems() { return problems; }
        public boolean permissionDenied() { return permissionDenied; }
    }

    public static List<String> apply(Path root, List<FileEdit> edits, PathPermissions permissions) {
        if (edits.isEmpty()) {
            throw new EditRejectedException(List.of("no edits returned"), false);
        }
        List<String> denied = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        for (FileEdit e : edits) {
            if (e.path() == null || e.path().isBlank()) {
                invalid.add("edit without a path");
                continue;
            }
            Path target = base.resolve(e.path()).normalize();
            if (Path.of(e.path()).isAbsolute() || !target.startsWith(base) || e.path().contains("..")) {
                denied.add("'" + e.path() + "' escapes the workspace");
                continue;
            }
            String rel = base.relativize(target).toString().replace('\\', '/');
            if (rel.startsWith(".git/") || rel.equals(".git")) {
                denied.add("'" + rel + "' is git metadata");
                continue;
            }
            Optional<String> refusal = permissions.check(rel);
            refusal.ifPresent(denied::add);
            if (refusal.isPresent()) {
                continue;
            }
            String action = e.action() == null ? "" : e.action();
            switch (action) {
                case "create" -> {
                    if (e.content() == null) invalid.add("create '" + rel + "' has no content");
                    if (Files.exists(target)) invalid.add("create '" + rel + "': file already exists, use replace or patch");
                }
                case "replace" -> {
                    if (e.content() == null) invalid.add("replace '" + rel + "' has no content");
                    if (!Files.isRegularFile(target)) invalid.add("replace '" + rel + "': file does not exist, use create");
                }
                case "delete" -> {
                    if (!Files.isRegularFile(target)) invalid.add("delete '" + rel + "': file does not exist");
                }
                case "patch" -> {
                    if (e.search() == null || e.search().isEmpty() || e.replace() == null) {
                        invalid.add("patch '" + rel + "' needs search and replace");
                    } else if (!Files.isRegularFile(target)) {
                        invalid.add("patch '" + rel + "': file does not exist");
                    } else {
                        int count = occurrences(read(target), e.search());
                        if (count != 1) {
                            invalid.add("patch '" + rel + "': search text found " + count + " times, must be exactly once");
                        }
                    }
                }
                default -> invalid.add("unknown action '" + action + "' for '" + rel + "'");
            }
        }
        if (!denied.isEmpty()) {
            throw new EditRejectedException(denied, true);
        }
        if (!invalid.isEmpty()) {
            throw new EditRejectedException(invalid, false);
        }
        List<String> touched = new ArrayList<>();
        for (FileEdit e : edits) {
            Path target = base.resolve(e.path()).normalize();
            try {
                switch (e.action()) {
                    case "create", "replace" -> {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, e.content(), StandardCharsets.UTF_8);
                    }
                    case "delete" -> Files.delete(target);
                    case "patch" -> Files.writeString(target, read(target).replace(e.search(), e.replace()),
                            StandardCharsets.UTF_8);
                    default -> throw new IllegalStateException(e.action());
                }
            } catch (IOException ex) {
                throw new EditRejectedException(List.of("cannot write '" + e.path() + "': " + ex.getMessage()), false);
            }
            touched.add(base.relativize(target).toString().replace('\\', '/'));
        }
        return touched;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EditRejectedException(List.of("cannot read '" + p.getFileName() + "'"), false);
        }
    }

    private static int occurrences(String text, String search) {
        int count = 0;
        for (int i = text.indexOf(search); i >= 0; i = text.indexOf(search, i + search.length())) {
            count++;
        }
        return count;
    }
}
