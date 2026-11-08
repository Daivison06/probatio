import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A small executable lease store and its verification run. The guarded variant checks ownership on
 * release; the unguarded one lets an expired holder delete the new owner's lease.
 *
 * <p>Run: {@code java examples/lease/LeaseExample.java --out DIR [--jar tla2tools.jar]}.
 */
public class LeaseExample {
    static final Map<String, String> EXPECTED_OWNERS = Map.of("Buggy", "", "Fixed", "B");

    static final class Lease {
        String owner = "";
        long deadline;
        final boolean guarded;

        Lease(boolean guarded) {
            this.guarded = guarded;
        }

        boolean acquire(String actor, long ttlNanos) {
            if (!owner.isEmpty()) {
                return false;
            }
            owner = actor;
            deadline = System.nanoTime() + ttlNanos;
            return true;
        }

        void expire() {
            if (!owner.isEmpty() && System.nanoTime() >= deadline) {
                owner = "";
            }
        }

        void release(String actor) {
            if (!guarded || owner.equals(actor)) {
                owner = "";
            }
        }
    }

    /** Run the stale-release scenario, write its observed trace, and return the final owner. */
    static String scenario(Path output, boolean guarded) throws IOException, InterruptedException {
        Lease store = new Lease(guarded);
        Map<String, Long> sequence = new LinkedHashMap<>();
        List<Map<String, Object>> events = new ArrayList<>();
        Observer observe = (actor, action, work) -> {
            long start = System.nanoTime();
            work.run();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("owner", store.owner);
            state.put("guarded", store.guarded);
            long end = System.nanoTime();
            sequence.merge(actor, 1L, Long::sum);
            events.add(event(actor, sequence.get(actor), start, end, action, Map.<String, Object>of("key", "example"), state));
        };
        observe.step("A", "acquire", () -> store.acquire("A", 1_000_000));
        while (System.nanoTime() < store.deadline) {
            Thread.sleep(1);
        }
        observe.step("clock", "expire", store::expire);
        observe.step("B", "acquire", () -> store.acquire("B", 60_000_000_000L));
        observe.step("A", "release", () -> store.release("A"));
        writeEvents(output, events);
        return store.owner;
    }

    interface Observer {
        void step(String actor, String action, Runnable work);
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> options = options(argv);
        Path output = Path.of(options.get("out")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Map<String, String> owners = new LinkedHashMap<>();
        for (String variant : List.of("Buggy", "Fixed")) {
            owners.put(variant, scenario(output.resolve(variant + ".ndjson"), variant.equals("Fixed")));
        }
        if (!owners.equals(EXPECTED_OWNERS)) {
            throw new IllegalStateException("Implementation did not reproduce the expected owners: " + owners);
        }
        writeControls(output);
        Files.writeString(output.resolve("suite.json"), suite());
        Files.writeString(output.resolve("controls.json"), """
            {"real_traces": ["Buggy.ndjson", "Fixed.ndjson"],
             "synthetic_negative_controls": ["badstate.ndjson", "unknown.ndjson", "empty.ndjson", "malformed.ndjson"]}
            """);
        System.exit(regress(output, options));
    }

    /** Derive synthetic negative controls from the real guarded trace. */
    static void writeControls(Path output) throws IOException {
        List<String> fixed = Files.readAllLines(output.resolve("Fixed.ndjson"));
        String last = fixed.get(fixed.size() - 1);
        Files.write(output.resolve("badstate.ndjson"), replaceLast(fixed, last.replace("\"owner\": \"B\"", "\"owner\": \"\"")));
        Files.write(output.resolve("unknown.ndjson"), replaceLast(fixed, last.replace("\"release\"", "\"unknown_action\"")));
        Files.writeString(output.resolve("empty.ndjson"), "");
        Files.writeString(output.resolve("malformed.ndjson"), "{");
    }

    static List<String> replaceLast(List<String> lines, String replacement) {
        List<String> copy = new ArrayList<>(lines);
        copy.set(copy.size() - 1, replacement);
        return copy;
    }

    static String suite() {
        Path model = here().resolve("Lease.tla");
        List<String> cases = new ArrayList<>();
        cases.add(mcCase("Buggy", model, "violation"));
        cases.add(mcCase("Fixed", model, "pass"));
        Map<String, String> traces = new LinkedHashMap<>();
        traces.put("Buggy", "match");
        traces.put("Fixed", "match");
        traces.put("badstate", "mismatch");
        traces.put("unknown", "mismatch");
        traces.put("empty", "invalid");
        traces.put("malformed", "invalid");
        traces.forEach((name, expected) -> cases.add(String.format(
            "{\"name\": \"trace-%s\", \"model\": %s, \"trace\": \"%s.ndjson\", \"expected\": \"%s\"}",
            name, quote(model.toString()), name, expected)));
        return cases.stream().collect(Collectors.joining(",\n  ", "[\n  ", "\n]\n"));
    }

    static String mcCase(String variant, Path model, String expected) {
        return String.format("{\"name\": \"mc-%s\", \"model\": %s, \"config\": %s, \"expected\": \"%s\"}",
            variant, quote(model.toString()), quote(here().resolve(variant + ".cfg").toString()), expected);
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
        return source == null ? Path.of("examples", "lease").toAbsolutePath() : Path.of(source).toAbsolutePath().getParent();
    }

    static Map<String, String> options(String[] argv) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < argv.length; i += 2) {
            if (!Set.of("--out", "--jar").contains(argv[i])) {
                throw new IllegalArgumentException("usage: LeaseExample.java --out DIR [--jar tla2tools.jar]");
            }
            options.put(argv[i].substring(2), argv[i + 1]);
        }
        if (!options.containsKey("out") || argv.length % 2 != 0) {
            throw new IllegalArgumentException("usage: LeaseExample.java --out DIR [--jar tla2tools.jar]");
        }
        return options;
    }

    static Map<String, Object> event(String actor, long seq, long start, long end, String action,
                                     Map<String, Object> data, Map<String, Object> state) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("actor", actor);
        event.put("seq", seq);
        event.put("start_ns", start);
        event.put("end_ns", end);
        event.put("action", action);
        event.put("data", data);
        event.put("state", state);
        return event;
    }

    static void writeEvents(Path path, List<Map<String, Object>> events) throws IOException {
        Files.writeString(path, events.stream().map(LeaseExample::json).collect(Collectors.joining("\n", "", "\n")));
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
