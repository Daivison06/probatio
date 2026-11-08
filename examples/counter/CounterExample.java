import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;

/**
 * Two real threads demonstrate a lost update and an atomic corrected variant.
 *
 * <p>Run: {@code java examples/counter/CounterExample.java --out DIR [--jar tla2tools.jar]}.
 */
public class CounterExample {
    static final Map<String, Long> EXPECTED_VALUES = Map.of("Buggy", 1L, "Fixed", 2L);

    /** Run both workers, write the observed trace, and return the final counter value. */
    static long scenario(Path output, boolean atomic) throws Exception {
        long[] value = {0};
        List<Map<String, Object>> events = new ArrayList<>();
        Object observation = new Object();
        CyclicBarrier readsDone = new CyclicBarrier(2);

        Step step = (actor, action, seq, work) -> {
            synchronized (observation) {
                long start = System.nanoTime();
                value[0] = work.applyAsLong(value[0]);
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("value", value[0]);
                state.put("atomic", atomic);
                events.add(event(actor, seq, start, System.nanoTime(), action, state));
            }
        };
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (String actor : List.of("A", "B")) {
            Thread thread = new Thread(() -> {
                try {
                    if (atomic) {
                        step.run(actor, "increment", 1, v -> v + 1);
                        return;
                    }
                    long[] cached = {0};
                    step.run(actor, "read", 1, v -> cached[0] = v);
                    readsDone.await(5, TimeUnit.SECONDS);
                    step.run(actor, "write", 2, v -> cached[0] + 1);
                } catch (Exception e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
            if (thread.isAlive()) {
                throw new IllegalStateException("Worker did not finish");
            }
        }
        if (!failures.isEmpty() || events.size() != (atomic ? 2 : 4)) {
            throw new IllegalStateException("Incomplete trace: " + failures);
        }
        writeEvents(output, events);
        return value[0];
    }

    interface Step {
        void run(String actor, String action, long seq, LongUnaryOperator work);
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> options = options(argv);
        Path output = Path.of(options.get("out")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("Buggy", scenario(output.resolve("Buggy.ndjson"), false));
        values.put("Fixed", scenario(output.resolve("Fixed.ndjson"), true));
        if (!values.equals(EXPECTED_VALUES)) {
            throw new IllegalStateException("Implementation did not reproduce the expected values: " + values);
        }
        Files.writeString(output.resolve("implementation-results.json"), json(values) + "\n");
        Files.writeString(output.resolve("suite.json"), suite());
        System.exit(regress(output, options));
    }

    static String suite() {
        String model = quote(here().resolve("Counter.tla").toString());
        List<String> cases = new ArrayList<>();
        for (String variant : List.of("Buggy", "Fixed")) {
            String expected = variant.equals("Fixed") ? "pass" : "violation";
            cases.add(String.format("{\"name\": \"mc-%s\", \"model\": %s, \"config\": %s, \"expected\": \"%s\"}",
                variant, model, quote(here().resolve(variant + ".cfg").toString()), expected));
            cases.add(String.format("{\"name\": \"trace-%s\", \"model\": %s, \"trace\": \"%s.ndjson\", \"expected\": \"match\"}",
                variant, model, variant));
        }
        return cases.stream().collect(Collectors.joining(",\n  ", "[\n  ", "\n]\n"));
    }

    static int regress(Path output, Map<String, String> options) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, here().resolve("../../scripts/Probatio.java").normalize().toString(),
            "regress", output.resolve("suite.json").toString(), "--out", output.resolve("regression").toString()));
        if (options.containsKey("jar")) {
            command.addAll(List.of("--jar", options.get("jar")));
        }
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    static Path here() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        return source == null ? Path.of("examples", "counter").toAbsolutePath() : Path.of(source).toAbsolutePath().getParent();
    }

    static Map<String, String> options(String[] argv) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            if (!Set.of("--out", "--jar").contains(argv[i])) {
                throw new IllegalArgumentException("usage: CounterExample.java --out DIR [--jar tla2tools.jar]");
            }
            options.put(argv[i].substring(2), argv[i + 1]);
        }
        if (!options.containsKey("out") || argv.length % 2 != 0) {
            throw new IllegalArgumentException("usage: CounterExample.java --out DIR [--jar tla2tools.jar]");
        }
        return options;
    }

    static Map<String, Object> event(String actor, long seq, long start, long end, String action, Map<String, Object> state) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("actor", actor);
        event.put("seq", seq);
        event.put("start_ns", start);
        event.put("end_ns", end);
        event.put("action", action);
        event.put("data", Map.of("increment", 1));
        event.put("state", state);
        return event;
    }

    static void writeEvents(Path path, List<Map<String, Object>> events) throws IOException {
        Files.writeString(path, events.stream().map(CounterExample::json).collect(Collectors.joining("\n", "", "\n")));
    }

    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> quote(e.getKey().toString()) + ": " + json(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
        }
        return value instanceof String s ? quote(s) : String.valueOf(value);
    }

    static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
