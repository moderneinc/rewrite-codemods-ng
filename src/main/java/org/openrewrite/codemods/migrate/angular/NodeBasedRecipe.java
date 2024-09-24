/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.codemods.migrate.angular;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.nodejs.NpmExecutor;
import org.openrewrite.nodejs.NpmExecutorExecutionContextView;
import org.openrewrite.quark.Quark;
import org.openrewrite.scheduling.WorkingDirectoryExecutionContextView;
import org.openrewrite.text.PlainText;
import org.openrewrite.tree.ParseError;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;

public abstract class NodeBasedRecipe extends ScanningRecipe<NodeBasedRecipe.Accumulator> {
    private static final String FIRST_RECIPE = NodeBasedRecipe.class.getName() + ".FIRST_RECIPE";
    private static final String PREVIOUS_RECIPE = NodeBasedRecipe.class.getName() + ".PREVIOUS_RECIPE";
    private static final String INIT_REPO_DIR = NodeBasedRecipe.class.getName() + ".INIT_REPO_DIR";

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Path directory = createDirectory(ctx, "repo");
        if (ctx.getMessage(INIT_REPO_DIR) == null) {
            ctx.putMessage(INIT_REPO_DIR, directory);
            ctx.putMessage(FIRST_RECIPE, ctx.getCycleDetails().getRecipePosition());
        }
        return new Accumulator(directory);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile && !(tree instanceof Quark) && !(tree instanceof ParseError) &&
                    !tree.getClass().getName().equals("org.openrewrite.java.tree.J$CompilationUnit")) {
                    SourceFile sourceFile = (SourceFile) tree;
                    String fileName = sourceFile.getSourcePath().getFileName().toString();
                    if (fileName.indexOf('.') > 0) {
                        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
                        acc.extensionCounts.computeIfAbsent(extension, e -> new AtomicInteger(0)).incrementAndGet();
                    }

                    // only extract initial source files for first codemod recipe
                    if (Objects.equals(ctx.getMessage(FIRST_RECIPE), ctx.getCycleDetails().getRecipePosition())) {
                        // FIXME filter out more source types; possibly only write plain text, json, and
                        // yaml?
                        acc.writeSource(sourceFile);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        Path previous = ctx.getMessage(PREVIOUS_RECIPE);
        if (previous != null
            && !Objects.equals(ctx.getMessage(FIRST_RECIPE), ctx.getCycleDetails().getRecipePosition())) {
            acc.copyFromPrevious(previous);
        }

        runNode(acc, ctx);
        ctx.putMessage(PREVIOUS_RECIPE, acc.getDirectory());

        // FIXME check for generated files
        return emptyList();
    }

    private void runNode(Accumulator acc, ExecutionContext ctx) {
        Path dir = acc.getDirectory();

        Path angularJsonPath = dir.resolve("angular.json");
        // Check if the file exists
        if (!Files.exists(angularJsonPath)) {
            throw new RuntimeException("angular.json file not found in the project directory: " + dir);
        }

        Path nodeModules = createDirectory(ctx, "recipe-run-modules");
        boolean useNvmExec = useNvmExec(acc, ctx);
        List<String> command = getNpmCommand(acc, ctx);

        if (command.isEmpty()) {
            return;
        }

        NpmExecutor npmShellExecutor = NpmExecutorExecutionContextView.view(ctx).getNpmExecutor().withConfigurationDirectory(dir);
        npmShellExecutor.init();

        command.replaceAll(s -> s
                .replace("${nodeModules}", nodeModules.toString())
                .replace("${repoDir}", ".")
                .replace("${parser}", acc.parser()));

        String angularCliVersion = getAngularCliPackage(acc, ctx);
        String npmrcPath = new File(dir.toString(), ".npmrc").getAbsolutePath();
        List<String> installNodeGypAndNan = new ArrayList<>(Arrays.asList("npm", "install", "--userconfig", npmrcPath, "--prefix", nodeModules.toString(), "--force", "--ignore-script", "node-gyp@10", "nan@2"));
        List<String> prefixedInstallAngularCli = new ArrayList<>(Arrays.asList("npm", "install", "--userconfig", npmrcPath, "--prefix", nodeModules.toString(), "--force", "--ignore-scripts", angularCliVersion));
        List<String> localNpmInstallCommand = new ArrayList<>(Arrays.asList("npm", "install", "--force", "--ignore-scripts"));

        try {
            if (useNvmExec) {
                installNodeGypAndNan.add(0, "nvm-exec");
                prefixedInstallAngularCli.add(0, "nvm-exec");
                localNpmInstallCommand.add(0, "nvm-exec");
                command.add(0, "nvm-exec");
            }

            Map<String, String> environment = new HashMap<>();
            environment.put("NG_DISABLE_VERSION_CHECK", "1");
            environment.put("NG_CLI_ANALYTICS", "false");
            environment.put("NODE_OPTIONS", "--max-old-space-size=2048");
            environment.put("NODE_PATH", nodeModules.toString());
            environment.put("TERM", "dumb");

            // Install node-gyp to avoid issues with `npx`
            npmShellExecutor.exec(installNodeGypAndNan, dir, environment, ctx);
            // install angular cli in the project
            npmShellExecutor.exec(prefixedInstallAngularCli, dir, environment, ctx);
            // install the project dependencies
            npmShellExecutor.exec(localNpmInstallCommand, dir, environment, ctx);

            // run `ng update` command
            Path out = npmShellExecutor.exec(command, dir, environment, ctx);

            for (Map.Entry<Path, Long> entry : acc.beforeModificationTimestamps.entrySet()) {
                Path path = entry.getKey();
                if (!Files.exists(path) || Files.getLastModifiedTime(path).toMillis() > entry.getValue()) {
                    acc.modified(path);
                }
            }
            processOutput(out, acc, ctx);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // Restore npm settings
            npmShellExecutor.postExec();
        }
    }

    protected abstract List<String> getNpmCommand(Accumulator acc, ExecutionContext ctx);

    // abstract method to return a boolean value for whether to use nvm-exec ahead
    // of commands
    protected abstract boolean useNvmExec(Accumulator acc, ExecutionContext ctx);

    protected abstract String getAngularCliPackage(Accumulator acc, ExecutionContext ctx);

    protected void processOutput(Path out, Accumulator acc, ExecutionContext ctx) {
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    // TODO parse sources like JSON where parser doesn't require an environment
                    return createAfter(sourceFile, acc, ctx);
                }
                return tree;
            }
        };
    }

    protected SourceFile createAfter(SourceFile before, Accumulator acc, ExecutionContext ctx) {
        if (!acc.wasModified(before)) {
            return before;
        }
        return new PlainText(
                before.getId(),
                before.getSourcePath(),
                before.getMarkers(),
                before.getCharset() != null ? before.getCharset().name() : null,
                before.isCharsetBomMarked(),
                before.getFileAttributes(),
                null,
                acc.content(before),
                emptyList());
    }

    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static class Accumulator {
        @Getter
        final Path directory;

        final Map<Path, Long> beforeModificationTimestamps = new HashMap<>();
        final Set<Path> modified = new LinkedHashSet<>();
        final Map<String, AtomicInteger> extensionCounts = new HashMap<>();
        final Map<String, Object> data = new HashMap<>();

        public void copyFromPrevious(Path previous) {
            try {
                Files.walkFileTree(previous, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path target = directory.resolve(previous.relativize(dir));
                        if (!target.equals(directory)) {
                            Files.createDirectory(target);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            Path target = directory.resolve(previous.relativize(file));
                            Files.copy(file, target);
                            beforeModificationTimestamps.put(target, Files.getLastModifiedTime(target).toMillis());
                        } catch (NoSuchFileException ignore) {
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String parser() {
            if (extensionCounts.containsKey("tsx")) {
                return "tsx";
            } else if (extensionCounts.containsKey("ts")) {
                return "ts";
            } else {
                return "babel";
            }
        }

        public void writeSource(SourceFile tree) {
            try {
                Path path = resolvedPath(tree);
                Files.createDirectories(path.getParent());
                PrintOutputCapture.MarkerPrinter markerPrinter = new PrintOutputCapture.MarkerPrinter() {
                };
                Path written = Files.write(path, tree.printAll(new PrintOutputCapture<>(0, markerPrinter))
                        .getBytes(tree.getCharset() != null ? tree.getCharset() : StandardCharsets.UTF_8));
                beforeModificationTimestamps.put(written, Files.getLastModifiedTime(written).toMillis());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void modified(Path path) {
            modified.add(path);
        }

        public boolean wasModified(SourceFile tree) {
            return modified.contains(resolvedPath(tree));
        }

        public String content(SourceFile tree) {
            try {
                Path path = resolvedPath(tree);
                return tree.getCharset() != null ? new String(Files.readAllBytes(path), tree.getCharset())
                        : new String(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public Path resolvedPath(SourceFile tree) {
            return directory.resolve(tree.getSourcePath());
        }

        public <T> void putData(String key, T value) {
            data.put(key, value);
        }

        @Nullable
        public <T> T getData(String key) {
            // noinspection unchecked
            return (T) data.get(key);
        }
    }

    protected static Path createDirectory(ExecutionContext ctx, String prefix) {
        WorkingDirectoryExecutionContextView view = WorkingDirectoryExecutionContextView.view(ctx);
        return Optional.of(view.getWorkingDirectory())
                .map(d -> d.resolve(prefix))
                .map(d -> {
                    try {
                        // if the directory already exists, return it
                        if (Files.exists(d)) {
                            return d.toRealPath();
                        }
                        return Files.createDirectory(d).toRealPath();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException("Failed to create working directory for " + prefix));
    }

}
