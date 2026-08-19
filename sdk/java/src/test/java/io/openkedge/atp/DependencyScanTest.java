package io.openkedge.atp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dependency allowlist (operational invariant jdk-only-crypto): the SDK's main
 * sources import only the JDK ({@code java.*}) and its own packages — no
 * third-party dependency, and no third-party cryptography.
 */
public final class DependencyScanTest {
    private DependencyScanTest() {}

    public static void noThirdPartyImports() throws IOException {
        Path srcDir = Path.of(System.getProperty("atp.src.dir", "src/main/java"));
        try (Stream<Path> paths = Files.walk(srcDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            Check.isTrue(!javaFiles.isEmpty(), "found main sources under " + srcDir);
            for (Path f : javaFiles) {
                for (String line : Files.readAllLines(f)) {
                    String s = line.strip();
                    if (!s.startsWith("import ")) {
                        continue;
                    }
                    String imported = s.substring("import ".length()).replace("static ", "").strip();
                    boolean ok = imported.startsWith("java.") || imported.startsWith("io.openkedge.atp");
                    Check.isTrue(ok, "non-allowlisted import in " + f.getFileName() + ": " + s);
                }
            }
        }
    }
}
