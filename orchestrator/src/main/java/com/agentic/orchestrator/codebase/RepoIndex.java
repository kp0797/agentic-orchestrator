package com.agentic.orchestrator.codebase;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A static index of a Java/Maven project: every top-level type with its methods, the repository types
 * it references, HTTP endpoints it declares and SQL tables it touches; plus the Flyway migrations and
 * the tables they define. Built with JavaParser (syntax only, no classpath), so it works on code that
 * does not compile yet and never runs any of it.
 */
public final class RepoIndex {

    /** One top-level type. {@code references} are simple names of other types in this repository. */
    public record TypeInfo(String fqn, String simpleName, String file, String kind, boolean test,
                           List<String> methods, Set<String> references, List<String> endpoints,
                           Set<String> tables, List<String> annotations, Set<String> paths) {}

    public record Migration(String file, int version, Set<String> tables) {}

    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:FROM|INTO|UPDATE|JOIN|TABLE|REFERENCES)\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?i)^\\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\\b");
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Set<String> SQL_NOISE = Set.of("if", "exists", "not", "only", "select", "set", "where");
    private static final Set<String> MAPPINGS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "PatchMapping", "DeleteMapping");

    private final Path root;
    private final List<TypeInfo> types;
    private final List<Migration> migrations;
    private final List<String> unparsable;

    private RepoIndex(Path root, List<TypeInfo> types, List<Migration> migrations, List<String> unparsable) {
        this.root = root;
        this.types = List.copyOf(types);
        this.migrations = List.copyOf(migrations);
        this.unparsable = List.copyOf(unparsable);
    }

    public static RepoIndex build(Path root) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        List<Path> javaFiles = files(root, p -> p.toString().endsWith(".java"));
        List<String> unparsable = new ArrayList<>();
        List<CompilationUnit> units = new ArrayList<>();
        Map<CompilationUnit, String> fileOf = new HashMap<>();
        for (Path f : javaFiles) {
            String rel = rel(root, f);
            try {
                ParseResult<CompilationUnit> r = parser.parse(f);
                if (r.isSuccessful() && r.getResult().isPresent()) {
                    units.add(r.getResult().get());
                    fileOf.put(r.getResult().get(), rel);
                } else {
                    unparsable.add(rel);
                }
            } catch (IOException e) {
                unparsable.add(rel);
            }
        }
        Set<String> known = new TreeSet<>();
        for (CompilationUnit cu : units) {
            cu.getTypes().forEach(t -> known.add(t.getNameAsString()));
        }
        List<TypeInfo> types = new ArrayList<>();
        for (CompilationUnit cu : units) {
            String file = fileOf.get(cu);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            for (TypeDeclaration<?> t : cu.getTypes()) {
                types.add(describe(t, pkg, file, known));
            }
        }
        types.sort((a, b) -> a.fqn().compareTo(b.fqn()));
        List<Migration> migrations = new ArrayList<>();
        for (Path f : files(root, p -> p.toString().replace('\\', '/').contains("/db/migration/")
                && p.getFileName().toString().endsWith(".sql"))) {
            Matcher m = MIGRATION_NAME.matcher(f.getFileName().toString());
            int version = m.matches() ? Integer.parseInt(m.group(1)) : -1;
            migrations.add(new Migration(rel(root, f), version, tables(read(f))));
        }
        migrations.sort((a, b) -> Integer.compare(a.version(), b.version()));
        Set<String> defined = new TreeSet<>();
        migrations.forEach(m -> defined.addAll(m.tables()));
        if (!defined.isEmpty()) {
            types.replaceAll(t -> {
                Set<String> real = new TreeSet<>(t.tables());
                real.retainAll(defined);
                return new TypeInfo(t.fqn(), t.simpleName(), t.file(), t.kind(), t.test(), t.methods(), t.references(),
                        t.endpoints(), real, t.annotations(), t.paths());
            });
        }
        return new RepoIndex(root, types, migrations, unparsable);
    }

    private static TypeInfo describe(TypeDeclaration<?> t, String pkg, String file, Set<String> known) {
        String name = t.getNameAsString();
        Set<String> refs = new TreeSet<>();
        t.findAll(ClassOrInterfaceType.class).forEach(c -> refs.add(c.getNameAsString()));
        t.findAll(NameExpr.class).forEach(n -> refs.add(n.getNameAsString()));
        refs.retainAll(known);
        refs.remove(name);
        List<String> methods = t.getMethods().stream().map(MethodDeclaration::getNameAsString).distinct().toList();
        List<String> literals = new ArrayList<>();
        t.findAll(StringLiteralExpr.class).forEach(s -> literals.add(s.getValue()));
        t.findAll(TextBlockLiteralExpr.class).forEach(s -> literals.add(s.getValue()));
        Set<String> tables = new TreeSet<>();
        literals.stream().filter(l -> SQL_STATEMENT.matcher(l).find()).forEach(l -> tables.addAll(tables(l)));
        // URL paths a type uses: how HTTP-level tests are linked to the controllers they exercise.
        Set<String> paths = new TreeSet<>();
        literals.stream().filter(l -> l.startsWith("/") && l.length() > 1 && !l.contains(" ")).forEach(paths::add);
        List<String> annotations = t.getAnnotations().stream().map(AnnotationExpr::getNameAsString).toList();
        String kind = t.isClassOrInterfaceDeclaration() && t.asClassOrInterfaceDeclaration().isInterface() ? "interface"
                : t.isRecordDeclaration() ? "record" : t.isEnumDeclaration() ? "enum" : "class";
        return new TypeInfo(pkg + name, name, file, kind, file.startsWith("src/test/"), methods, refs,
                endpoints(t), tables, annotations, paths);
    }

    /** HTTP endpoints from Spring mapping annotations, class-level prefix included. */
    private static List<String> endpoints(TypeDeclaration<?> t) {
        String prefix = t.getAnnotations().stream().filter(a -> a.getNameAsString().equals("RequestMapping"))
                .findFirst().flatMap(RepoIndex::mappingPath).orElse("");
        List<String> out = new ArrayList<>();
        for (MethodDeclaration m : t.getMethods()) {
            for (AnnotationExpr a : m.getAnnotations()) {
                String n = a.getNameAsString();
                if (MAPPINGS.contains(n)) {
                    String verb = n.equals("RequestMapping") ? "ANY" : n.replace("Mapping", "").toUpperCase(Locale.ROOT);
                    out.add(verb + " " + prefix + mappingPath(a).orElse(""));
                }
            }
        }
        return out;
    }

    private static Optional<String> mappingPath(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s && s.getMemberValue().isStringLiteralExpr()) {
            return Optional.of(s.getMemberValue().asStringLiteralExpr().getValue());
        }
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if ((p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                        && p.getValue().isStringLiteralExpr()) {
                    return Optional.of(p.getValue().asStringLiteralExpr().getValue());
                }
            }
        }
        return Optional.empty();
    }

    static Set<String> tables(String sql) {
        Set<String> out = new TreeSet<>();
        Matcher m = SQL_TABLE.matcher(sql);
        while (m.find()) {
            String t = m.group(1).toLowerCase(Locale.ROOT);
            if (!SQL_NOISE.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public Path root() { return root; }
    public List<TypeInfo> types() { return types; }
    public List<Migration> migrations() { return migrations; }
    public List<String> unparsable() { return unparsable; }

    public boolean isEmpty() {
        return types.stream().noneMatch(t -> !t.test()) && migrations.isEmpty();
    }

    public List<TypeInfo> mainTypes() {
        return types.stream().filter(t -> !t.test()).toList();
    }

    public List<TypeInfo> testTypes() {
        return types.stream().filter(TypeInfo::test).toList();
    }

    public Optional<TypeInfo> type(String simpleName) {
        return types.stream().filter(t -> t.simpleName().equals(simpleName) && !t.test()).findFirst();
    }

    /** Main types that reference any of the given types. */
    public Set<String> dependentsOf(Collection<String> simpleNames) {
        Set<String> out = new LinkedHashSet<>();
        for (TypeInfo t : mainTypes()) {
            if (t.references().stream().anyMatch(simpleNames::contains) && !simpleNames.contains(t.simpleName())) {
                out.add(t.simpleName());
            }
        }
        return out;
    }

    public int nextMigrationVersion() {
        return migrations.stream().mapToInt(Migration::version).max().orElse(0) + 1;
    }

    private static List<Path> files(Path root, java.util.function.Predicate<Path> filter) {
        Path src = root.resolve("src");
        if (!Files.isDirectory(src)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(Files::isRegularFile).filter(filter).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            return "";
        }
    }
}
