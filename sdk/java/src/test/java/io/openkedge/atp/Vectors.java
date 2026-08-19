package io.openkedge.atp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads the checked-in golden vector files (test-vectors/*.json). These are the
 * normative bytes a Conformant Producer must reproduce without modification
 * (ATP-0001 §18.2).
 */
public final class Vectors {
    private Vectors() {}

    private static Path dir() {
        String override = System.getProperty("atp.vectors.dir",
                System.getenv("ATP_VECTORS_DIR"));
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        return Path.of("..", "..", "test-vectors");
    }

    public static Map<String, Object> load(String name) {
        try {
            String text = Files.readString(dir().resolve(name));
            return Json.obj(Json.parse(text));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read vector " + name + " from " + dir(), e);
        }
    }
}
