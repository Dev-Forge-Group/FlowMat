package org.myweb.flowmat.testimpact;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Leaving out backend test classes that already passed against the same code (docs/operations/ci-selection.md §3).
 *
 * <p>CI turns it on with system properties that build.gradle passes from {@code -PtestImpact.record} and
 * {@code -PtestImpact.previous}. {@link TestImpactListener} records, for each top-level test class, which project
 * classes it ran (from a JaCoCo dump taken when the class starts and ends) with a hash of each class file.
 * {@link TestImpactFilter} leaves out a class whose last run passed when every class it ran is unchanged, nothing
 * outside the classes changed ({@code testImpact.key}: resources, build files, fixtures, libraries), and no class that
 * affects every test changed. Off by default: a local run runs everything, as before.
 */
final class TestImpact {

    static final String FORMAT = "test-impact/1";
    static final String PROJECT = "org/myweb/flowmat/";
    static final ObjectMapper JSON = new ObjectMapper();

    /** One test class's last run: how it ended and the hash of each project class it ran. */
    record Entry(String format, String key, String outcome, Map<String, String> classes) {
        boolean passedWith(String currentKey) {
            return FORMAT.equals(format) && "passed".equals(outcome) && currentKey.equals(key);
        }
    }

    private static final TestImpact CURRENT = new TestImpact();
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    /** Where this run writes its records; null when recording is off. */
    final Path out;
    /** The last run's records, restored by CI; null runs every class. */
    final Path previous;
    final String key;
    final List<Path> classDirs;
    final Set<String> alwaysRun;

    private TestImpact() {
        out = path(System.getProperty("testImpact.out"));
        previous = path(System.getProperty("testImpact.previous"));
        key = System.getProperty("testImpact.key", "") + "|java " + System.getProperty("java.version");
        classDirs = Arrays.stream(System.getProperty("testImpact.classDirs", "").split(java.io.File.pathSeparator))
            .filter(part -> !part.isBlank()).map(Path::of).toList();
        alwaysRun = Arrays.stream(System.getProperty("testImpact.alwaysRun", "").split(","))
            .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toSet());
    }

    static TestImpact current() {
        return CURRENT;
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    static Path recordFile(Path dir, String testClass) {
        return dir.resolve("records").resolve(testClass + ".json");
    }

    /** Every project class file on the test class path when a run started, with its hash: what "new" is measured against. */
    static Path classListFile(Path dir) {
        return dir.resolve("classes.json");
    }

    /** The project class files in the class directories, by internal name, with their hashes. */
    Map<String, String> currentClasses() {
        Map<String, String> classes = new java.util.TreeMap<>();
        for (Path dir : classDirs) {
            Path project = dir.resolve(PROJECT);
            if (!Files.isDirectory(project)) continue;
            try (java.util.stream.Stream<Path> files = Files.walk(project)) {
                files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    String name = relative.substring(0, relative.length() - ".class".length());
                    classes.put(name, hashOfClass(name));
                });
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return classes;
    }

    static Path execFile(Path dir, String name) {
        return dir.resolve("exec").resolve(name + ".exec");
    }

    static Entry read(Path file) {
        try {
            return JSON.readValue(file.toFile(), Entry.class);
        } catch (IOException unreadable) {
            return null;
        }
    }

    static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** SHA-256 of a class file as the test class path has it, or "missing". */
    static String hashOfClass(String internalName) {
        return HASHES.computeIfAbsent(internalName, name -> {
            try (InputStream in = TestImpact.class.getClassLoader().getResourceAsStream(name + ".class")) {
                return in == null ? "missing" : sha256(in.readAllBytes());
            } catch (IOException unreadable) {
                return "unreadable";
            }
        });
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The classes with at least one executed probe in JaCoCo execution data (the exec file format: a header block, session
     * blocks, and one block per class with its id, name and probes as a packed boolean array).
     */
    static Set<String> executedClasses(byte[] exec) throws IOException {
        Set<String> names = new TreeSet<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(exec));
        while (true) {
            int block = in.read();
            if (block == -1) return names;
            switch (block) {
                case 0x01 -> {
                    if (in.readChar() != 0xC0C0) throw new IOException("not JaCoCo execution data");
                    in.readChar();
                }
                case 0x10 -> {
                    in.readUTF();
                    in.readLong();
                    in.readLong();
                }
                case 0x11 -> {
                    in.readLong();
                    String name = in.readUTF();
                    if (anyProbeHit(in)) names.add(name);
                }
                default -> throw new IOException("unknown block " + block + " in JaCoCo execution data");
            }
        }
    }

    private static boolean anyProbeHit(DataInputStream in) throws IOException {
        int length = varInt(in);
        boolean hit = false;
        for (int i = 0; i < (length + 7) / 8; i++) {
            if (in.readUnsignedByte() != 0) hit = true;
        }
        return hit;
    }

    private static int varInt(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if ((value & 0x80) == 0) return value;
        return (value & 0x7F) | (varInt(in) << 7);
    }

    /** What the JaCoCo agent of this JVM has collected since the last reset; null without an agent. */
    static byte[] dump(boolean reset) {
        try {
            Class<?> runtime = Class.forName("org.jacoco.agent.rt.RT");
            Class<?> agentType = Class.forName("org.jacoco.agent.rt.IAgent");
            Object agent = runtime.getMethod("getAgent").invoke(null);
            return (byte[]) agentType.getMethod("getExecutionData", boolean.class).invoke(agent, reset);
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException noAgent) {
            return null;
        }
    }
}
