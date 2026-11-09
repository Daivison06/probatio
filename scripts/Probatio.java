import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Probatio verification tools: a TLC runner with input snapshots and receipts, a trace compiler,
 * and a regression suite. Requires a JDK 17+; run directly with {@code java scripts/Probatio.java}.
 */
public class Probatio {
    static final String USAGE = """
        usage:
          java Probatio.java tlc <model.tla> <model.cfg> --out DIR [--jar JAR] [--java JAVA]
                                [--timeout SECONDS] [--workers N] [--heap 1g] [--trace]
          java Probatio.java trace <model.tla> <trace.ndjson> --out DIR
          java Probatio.java regress <suite.json> --out DIR [--jar JAR] [--java JAVA] [--timeout SECONDS]
        --jar defaults to $PROBATIO_HOME/tla2tools.jar (PROBATIO_HOME defaults to ~/.probatio).
        --java defaults to the java running this program.""";

    public static void main(String[] argv) {
        try {
            System.exit(dispatch(argv));
        } catch (ProbatioException | IOException | UncheckedIOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        } catch (RuntimeException e) {
            e.printStackTrace();
            System.exit(2); // Never 1: that code means "checked and not accepted".
        }
    }

    static int dispatch(String[] argv) throws IOException {
        if (argv.length == 0) {
            System.err.println(USAGE);
            return 2;
        }
        String[] rest = java.util.Arrays.copyOfRange(argv, 1, argv.length);
        switch (argv[0]) {
            case "tlc" -> {
                Args a = Args.parse(rest, 2, Set.of("out", "jar", "java", "timeout", "workers", "heap"), Set.of("trace"));
                Map<String, Object> receipt = Tlc.run(new Tlc.Request(
                    a.path(0), a.path(1), a.requiredPath("out"), a.jar(), a.java(),
                    a.number("timeout", 120), a.integer("workers", 2), a.option("heap", "1g"), a.flag("trace")));
                System.out.println(Json.write(receipt, false));
                return Set.of("pass", "match").contains(receipt.get("result")) ? 0 : 1;
            }
            case "trace" -> {
                Args a = Args.parse(rest, 2, Set.of("out"), Set.of());
                System.out.println(TraceCheck.compile(a.path(0), a.path(1), a.requiredPath("out")).model());
                return 0;
            }
            case "regress" -> {
                Args a = Args.parse(rest, 1, Set.of("out", "jar", "java", "timeout"), Set.of());
                List<Map<String, Object>> results = Regress.evaluate(
                    a.path(0), a.requiredPath("out"), a.jar(), a.java(), a.number("timeout", 120));
                return Regress.report(results);
            }
            default -> {
                System.err.println(USAGE);
                return 2;
            }
        }
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK provides SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void createFreshDirectory(Path dir) throws IOException {
        if (dir.getParent() != null) {
            Files.createDirectories(dir.getParent());
        }
        Files.createDirectory(dir);
    }
}

/** A setup or input problem the caller must fix; never a verification result. */
class ProbatioException extends RuntimeException {
    ProbatioException(String message) {
        super(message);
    }
}

/** The observed trace is not admissible evidence, as opposed to a tool or setup failure. */
class TraceRejected extends ProbatioException {
    TraceRejected(String message) {
        super(message);
    }
}

/** Runs TLC on an immutable snapshot of the model directory and classifies the outcome. */
final class Tlc {
    record Request(Path model, Path config, Path output, Path jar, String java,
                   double timeoutSeconds, int workers, String heap, boolean trace) {}

    static final Set<String> SNAPSHOT_SUFFIXES = Set.of(".tla", ".cfg", ".json", ".ndjson");
    static final Pattern HEAP = Pattern.compile("[1-9][0-9]*[mMgG]");
    static final Set<String> CFG_KEYWORDS = Set.of(
        "INIT", "NEXT", "SPECIFICATION", "CONSTANT", "CONSTANTS", "INVARIANT", "INVARIANTS", "PROPERTY",
        "PROPERTIES", "CONSTRAINT", "CONSTRAINTS", "ACTION_CONSTRAINT", "ACTION_CONSTRAINTS", "SYMMETRY",
        "VIEW", "CHECK_DEADLOCK", "POSTCONDITION", "ALIAS");
    static final Set<String> INVARIANT_SECTIONS = Set.of("INVARIANT", "INVARIANTS");
    static final Set<String> PROPERTY_SECTIONS = Set.of("INVARIANT", "INVARIANTS", "PROPERTY", "PROPERTIES");
    static final Pattern CFG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern INVARIANT_VIOLATED = Pattern.compile("Invariant (\\w+) is violated");
    static final Pattern INITIAL_STATES = Pattern.compile("Finished computing initial states:\\s+([0-9,]+) distinct state");
    static final Pattern TEMPORAL_VIOLATED = Pattern.compile("Temporal propert(?:y .+ was|ies were) violated");
    static final String NO_ERROR = "Model checking completed. No error has been found.";

    static Map<String, Object> run(Request r) throws IOException {
        Path model = r.model().toAbsolutePath().normalize();
        Path config = r.config().toAbsolutePath().normalize();
        Path output = r.output().toAbsolutePath().normalize();
        Path jar = r.jar().toAbsolutePath().normalize();
        checkArguments(r, model, config, jar);
        checkConfig(Files.readString(config), r.trace());
        Probatio.createFreshDirectory(output);
        Path inputs = output.resolve("inputs");
        Map<String, String> digests = snapshot(model.getParent(), inputs);
        List<String> command = command(r, jar, config.getFileName().toString(), output.resolve("states"),
            model.getFileName().toString());
        Path log = output.resolve("tlc.log");
        long start = System.nanoTime();
        Execution execution = execute(command, inputs, log, r.timeoutSeconds());
        Map<String, Object> receipt = classify(execution.exitCode(), readLog(log), r.trace());
        if (execution.timedOut()) {
            receipt.put("result", "timeout");
        }
        receipt.put("exit_code", execution.exitCode());
        receipt.put("seconds", Math.round((System.nanoTime() - start) / 1e6) / 1000.0);
        receipt.put("command", command);
        receipt.put("cwd", inputs.toString());
        receipt.put("inputs_sha256", digests);
        receipt.put("jar_sha256", Probatio.sha256(jar));
        receipt.put("log", log.toString());
        Files.writeString(output.resolve("receipt.json"), Json.write(receipt, true) + "\n");
        return receipt;
    }

    /** Map a TLC exit code to a result only when the log confirms it; anything else is an error. */
    static Map<String, Object> classify(Integer code, String log, boolean trace) {
        Matcher initial = INITIAL_STATES.matcher(log);
        Long initialStates = initial.find() ? Long.valueOf(initial.group(1).replace(",", "")) : null;
        Matcher violated = INVARIANT_VIOLATED.matcher(log);
        String invariant = violated.find() ? violated.group(1) : null;
        String result = switch (code == null ? -1 : code) {
            case 0 -> initialStates != null && initialStates > 0 && log.contains(NO_ERROR)
                ? (trace ? "mismatch" : "pass") : "error";
            case 11 -> log.contains("Deadlock reached") ? "deadlock" : "error";
            case 12 -> invariant == null ? "error"
                : trace && invariant.equals("TraceIncomplete") ? "match" : "violation";
            case 13 -> TEMPORAL_VIOLATED.matcher(log).find() ? "violation" : "error";
            default -> "error";
        };
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("result", result);
        outcome.put("invariant", invariant);
        outcome.put("initial_states", initialStates);
        return outcome;
    }

    record Execution(Integer exitCode, boolean timedOut) {}

    static Execution execute(List<String> command, Path cwd, Path log, double timeoutSeconds) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            Files.writeString(log, String.valueOf(e.getMessage()));
            return new Execution(null, false);
        }
        try {
            if (process.waitFor((long) (timeoutSeconds * 1e9), TimeUnit.NANOSECONDS)) {
                return new Execution(process.exitValue(), false);
            }
            stop(process);
            return new Execution(process.exitValue(), true);
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            return new Execution(null, false);
        }
    }

    /** Terminate TLC and anything it spawned; escalate if it ignores the polite request. */
    static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    static String readLog(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    }

    static List<String> command(Request r, Path jar, String config, Path metadir, String model) {
        List<String> command = new ArrayList<>(List.of(r.java(), "-XX:+UseParallelGC", "-Xmx" + r.heap(),
            "-cp", jar.toString(), "tlc2.TLC", "-workers", String.valueOf(r.workers()),
            "-config", config, "-metadir", metadir.toString()));
        if (r.trace()) {
            command.add("-deadlock"); // Failed trace branches are expected terminal states.
        }
        command.add(model);
        return command;
    }

    static void checkArguments(Request r, Path model, Path config, Path jar) {
        if (r.timeoutSeconds() <= 0 || r.workers() < 1 || !HEAP.matcher(r.heap()).matches()) {
            throw new ProbatioException("Positive timeout/workers and heap such as 512m or 1g required");
        }
        if (!model.getParent().equals(config.getParent())) {
            throw new ProbatioException("Model and configuration must share a directory");
        }
        if (!Files.isRegularFile(jar)) {
            throw new ProbatioException("TLC jar not found: " + jar);
        }
        if (!Files.isRegularFile(model) || !Files.isRegularFile(config)) {
            throw new ProbatioException("Model or configuration not found");
        }
    }

    static void checkConfig(String text, boolean trace) {
        Set<List<String>> properties = configuredProperties(text);
        boolean traceInvariant = properties.stream()
            .anyMatch(p -> INVARIANT_SECTIONS.contains(p.get(0)) && p.get(1).equals("TraceIncomplete"));
        if (trace && !traceInvariant) {
            throw new ProbatioException("Trace reachability invariant is not configured");
        }
        if (!trace && properties.isEmpty()) {
            throw new ProbatioException("No correctness property configured");
        }
    }

    /** (section, name) pairs for each property section followed by a real name, not an empty section. */
    static Set<List<String>> configuredProperties(String text) {
        String[] words = stripComments(text).trim().split("\\s+");
        Set<List<String>> properties = new LinkedHashSet<>();
        for (int i = 0; i + 1 < words.length; i++) {
            String name = words[i + 1];
            if (PROPERTY_SECTIONS.contains(words[i]) && CFG_NAME.matcher(name).matches() && !CFG_KEYWORDS.contains(name)) {
                properties.add(List.of(words[i], name));
            }
        }
        return properties;
    }

    /** Remove TLA+ line comments and nested block comments. */
    static String stripComments(String text) {
        StringBuilder kept = new StringBuilder();
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("(*", i)) {
                depth++;
                i += 2;
            } else if (depth > 0 && text.startsWith("*)", i)) {
                depth--;
                i += 2;
            } else if (depth > 0) {
                i++;
            } else if (text.startsWith("\\*", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else {
                kept.append(text.charAt(i++));
            }
        }
        return kept.toString();
    }

    static Map<String, String> snapshot(Path source, Path destination) throws IOException {
        Files.createDirectory(destination);
        Map<String, String> digests = new TreeMap<>();
        try (Stream<Path> files = Files.list(source)) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (Files.isRegularFile(path) && dot >= 0 && SNAPSHOT_SUFFIXES.contains(name.substring(dot))) {
                    Files.copy(path, destination.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                    digests.put(name, Probatio.sha256(path));
                }
            }
        }
        return digests;
    }
}

/** Compiles observed NDJSON into an existential TLC trace reachability check. */
final class TraceCheck {
    record Compiled(Path model, Path config) {}

    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    static final Set<String> TLA_RESERVED = Set.of((
        "ACTION ASSUME ASSUMPTION AXIOM BOOLEAN BY CASE CHOOSE CONSTANT CONSTANTS COROLLARY DEF DEFINE DEFS "
        + "DOMAIN ELSE ENABLED EXCEPT EXTENDS FALSE HAVE HIDE IF IN INSTANCE LAMBDA LEMMA LET LOCAL MODULE NEW "
        + "OBVIOUS OMITTED ONLY OTHER PICK PROOF PROPOSITION PROVE QED RECURSIVE STATE STRING SUBSET SUFFICES "
        + "TAKE TEMPORAL THEN THEOREM TRUE UNCHANGED UNION USE VARIABLE VARIABLES WITH WITNESS").split(" "));
    static final long TLC_INT_MAX = 2147483647L;
    static final Set<String> EVENT_FIELDS = Set.of("actor", "seq", "start_ns", "end_ns", "action", "state", "data");
    static final List<String> PROJECTED_FIELDS = List.of("actor", "action", "data", "state");
    static final List<String> COUNTERS = List.of("seq", "start_ns", "end_ns");
    static final Set<String> RESERVED_MODULES = Set.of("TraceCheck", "TraceInput");
    static final List<Map.Entry<String, Pattern>> MODEL_CONTRACT = List.of(
        Map.entry("Init", Pattern.compile("^\\s*Init\\s*==", Pattern.MULTILINE)),
        Map.entry("Step(e)", Pattern.compile("^\\s*Step\\s*\\(", Pattern.MULTILINE)),
        Map.entry("Observe", Pattern.compile("^\\s*Observe\\s*==", Pattern.MULTILINE)));
    static final String TRACE_CHECK = """
        ---- MODULE TraceCheck ----
        EXTENDS $model, TraceInput, Sequences, FiniteSets
        VARIABLE consumed
        TraceInit == /\\ Init /\\ consumed = {}
        TraceNext == \\E i \\in (1..Len(Events)) \\ consumed :
            /\\ Before[i] \\subseteq consumed
            /\\ Step(Events[i])
            /\\ Observe' = Events[i].state
            /\\ consumed' = consumed \\cup {i}
        TraceIncomplete == consumed /= 1..Len(Events)
        ====
        """;

    static Compiled compile(Path model, Path trace, Path output) throws IOException {
        checkModel(model);
        List<Map<String, Object>> events = readEvents(trace);
        Probatio.createFreshDirectory(output);
        for (Path source : tlaFiles(model.toAbsolutePath().getParent())) {
            Files.copy(source, output.resolve(source.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.copy(trace, output.resolve("source.ndjson"));
        Files.writeString(output.resolve("TraceInput.tla"), traceInputModule(events));
        Files.writeString(output.resolve("TraceCheck.tla"), TRACE_CHECK.replace("$model", stem(model)));
        Files.writeString(output.resolve("TraceCheck.cfg"), "INIT TraceInit\nNEXT TraceNext\nINVARIANT TraceIncomplete\n");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("events", events.size());
        metadata.put("semantics", "exists complete matching execution");
        metadata.put("clock", "shared monotonic clock required");
        Files.writeString(output.resolve("trace-metadata.json"), Json.write(metadata, true) + "\n");
        return new Compiled(output.resolve("TraceCheck.tla"), output.resolve("TraceCheck.cfg"));
    }

    static List<Map<String, Object>> readEvents(Path path) throws IOException {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object value : parseLines(path)) {
            events.add(checkEvent(value));
        }
        if (events.isEmpty()) {
            throw new TraceRejected("Empty trace is not evidence");
        }
        checkActorOrder(events);
        return events;
    }

    static List<Object> parseLines(Path path) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new TraceRejected("Trace is not valid UTF-8");
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            try {
                values.add(Json.parse(lines.get(i)));
            } catch (Json.SyntaxError e) {
                throw new TraceRejected("Trace line " + (i + 1) + " is not valid JSON: " + e.getMessage());
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> checkEvent(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(EVENT_FIELDS)) {
            throw new TraceRejected("Trace event must have exactly: " + String.join(", ", new TreeSet<>(EVENT_FIELDS)));
        }
        Map<String, Object> event = (Map<String, Object>) map;
        if (!COUNTERS.stream().allMatch(k -> event.get(k) instanceof Long n && n >= 0)) {
            throw new TraceRejected("Invalid event counter/time");
        }
        if (!(event.get("actor") instanceof String actor) || actor.isEmpty() || !(event.get("action") instanceof String)) {
            throw new TraceRejected("Actor must be a nonempty string and action a string");
        }
        if (counter(event, "start_ns") > counter(event, "end_ns")) {
            throw new TraceRejected("Inverted event interval");
        }
        if (!(event.get("state") instanceof Map<?, ?> state) || state.isEmpty()) {
            throw new TraceRejected("Every event requires observed state");
        }
        literal(project(event));
        return event;
    }

    static void checkActorOrder(List<Map<String, Object>> events) {
        Map<Object, List<Map<String, Object>>> byActor = events.stream()
            .collect(Collectors.groupingBy(e -> e.get("actor"), LinkedHashMap::new, Collectors.toList()));
        for (List<Map<String, Object>> group : byActor.values()) {
            List<Map<String, Object>> ordered = new ArrayList<>(group);
            ordered.sort((a, b) -> Long.compare(counter(a, "seq"), counter(b, "seq")));
            for (int i = 0; i < ordered.size(); i++) {
                if (counter(ordered.get(i), "seq") != i + 1) {
                    throw new TraceRejected("Per-actor sequence must start at 1 and be contiguous");
                }
                if (i > 0 && counter(ordered.get(i - 1), "end_ns") > counter(ordered.get(i), "start_ns")) {
                    throw new TraceRejected("Same-actor events overlap; use separate actors for concurrent operations");
                }
            }
        }
    }

    /**
     * 1-based indexes each event must follow; their transitive closure is the full causal order.
     *
     * <p>The order is the actor's program order plus real-time precedence (ended before this started).
     * Among real-time predecessors only the maximal ones are kept: an earlier event that ended before
     * another predecessor started is already implied through it.
     */
    static List<Set<Integer>> immediatePredecessors(List<Map<String, Object>> events) {
        Map<List<Object>, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < events.size(); i++) {
            indexOf.put(List.of(events.get(i).get("actor"), counter(events.get(i), "seq")), i + 1);
        }
        List<Set<Integer>> result = new ArrayList<>();
        for (Map<String, Object> event : events) {
            long start = counter(event, "start_ns");
            long horizon = events.stream().filter(o -> counter(o, "end_ns") < start)
                .mapToLong(o -> counter(o, "start_ns")).max().orElse(0);
            Set<Integer> before = new TreeSet<>();
            for (int j = 0; j < events.size(); j++) {
                long end = counter(events.get(j), "end_ns");
                if (end < start && end >= horizon) {
                    before.add(j + 1);
                }
            }
            Integer previous = indexOf.get(List.of(event.get("actor"), counter(event, "seq") - 1));
            if (previous != null) {
                before.add(previous);
            }
            result.add(before);
        }
        return result;
    }

    static String literal(Object value) {
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Long n) {
            if (n > TLC_INT_MAX || n < -TLC_INT_MAX) {
                throw new TraceRejected("Observed integers must fit TLC integers; abstract large values explicitly");
            }
            return n.toString();
        }
        if (value instanceof BigInteger) {
            throw new TraceRejected("Observed integers must fit TLC integers; abstract large values explicitly");
        }
        if (value instanceof String s) {
            return tlaString(s);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TraceCheck::literal).collect(Collectors.joining(", ", "<<", ">>"));
        }
        if (value instanceof Map<?, ?> map) {
            return record(map);
        }
        throw new TraceRejected("Unsupported TLA literal: use nonempty records, sequences, ASCII strings, integers, booleans");
    }

    static String tlaString(String s) {
        if (!s.chars().allMatch(c -> c >= 32 && c < 127)) {
            throw new TraceRejected("Observed strings must be printable ASCII");
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    static String record(Map<?, ?> map) {
        boolean validKeys = !map.isEmpty() && map.keySet().stream().allMatch(k -> {
            String key = (String) k;
            return IDENTIFIER.matcher(key).matches() && !TLA_RESERVED.contains(key)
                && !key.startsWith("WF_") && !key.startsWith("SF_");
        });
        if (!validKeys) {
            throw new TraceRejected("Records need nonempty, non-reserved TLA identifier keys");
        }
        return map.entrySet().stream().map(e -> e.getKey() + " |-> " + literal(e.getValue()))
            .collect(Collectors.joining(", ", "[", "]"));
    }

    static void checkModel(Path model) throws IOException {
        String stem = stem(model);
        if (!IDENTIFIER.matcher(stem).matches() || RESERVED_MODULES.contains(stem)) {
            throw new ProbatioException("Invalid or reserved module name: " + stem);
        }
        if (!Files.isRegularFile(model)) {
            throw new ProbatioException("Model not found: " + model);
        }
        StringBuilder source = new StringBuilder();
        for (Path path : tlaFiles(model.toAbsolutePath().getParent())) {
            source.append(Files.readString(path)).append('\n');
        }
        List<String> missing = MODEL_CONTRACT.stream()
            .filter(e -> !e.getValue().matcher(source).find()).map(Map.Entry::getKey).toList();
        if (!missing.isEmpty()) {
            throw new ProbatioException("Model must define " + String.join(", ", missing) + " for trace validation");
        }
    }

    static String traceInputModule(List<Map<String, Object>> events) {
        List<Object> projected = events.stream().<Object>map(TraceCheck::project).toList();
        String before = immediatePredecessors(events).stream()
            .map(p -> p.stream().map(String::valueOf).collect(Collectors.joining(", ", "{", "}")))
            .collect(Collectors.joining(", ", "<<", ">>"));
        return "---- MODULE TraceInput ----\nEXTENDS Integers\nEvents == " + literal(projected)
            + "\nBefore == " + before + "\n====\n";
    }

    static Map<String, Object> project(Map<String, Object> event) {
        Map<String, Object> projected = new LinkedHashMap<>();
        PROJECTED_FIELDS.forEach(k -> projected.put(k, event.get(k)));
        return projected;
    }

    static List<Path> tlaFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".tla") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".tla") ? name.substring(0, name.length() - 4) : name;
    }

    static long counter(Map<String, Object> event, String key) {
        return (Long) event.get(key);
    }
}

/** Reruns model configurations and implementation traces as one regression suite. */
final class Regress {
    record Case(String name, Path model, String expected, Path config, Path trace) {}

    /** "error" and "timeout" are absent on purpose: a broken run can never satisfy an expectation. */
    static final Set<String> EXPECTABLE = Set.of("pass", "violation", "deadlock", "match", "mismatch", "invalid");
    static final Set<String> REQUIRED_KEYS = Set.of("name", "model", "expected");
    static final Set<String> SOURCE_KEYS = Set.of("config", "trace");
    static final Pattern CASE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    static List<Map<String, Object>> evaluate(Path manifest, Path output, Path jar, String java, double timeout)
            throws IOException {
        manifest = manifest.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        List<Case> cases = loadCases(manifest);
        Probatio.createFreshDirectory(output);
        Files.copy(manifest, output.resolve("manifest.json"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Case c : cases) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", c.name());
            result.put("expected", c.expected());
            Map.Entry<String, Object> outcome;
            try {
                outcome = runCase(c, output, jar, java, timeout);
            } catch (IOException | RuntimeException e) {
                outcome = Map.<String, Object>entry("error",
                    Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            result.put("actual", outcome.getKey());
            result.put("accepted", outcome.getKey().equals(c.expected()));
            result.put("evidence", outcome.getValue());
            results.add(result);
            Files.writeString(output.resolve("results.json"), Json.write(results, true) + "\n");
        }
        return results;
    }

    static int report(List<Map<String, Object>> results) {
        List<Map<String, Object>> summary = results.stream().map(r -> {
            Map<String, Object> s = new LinkedHashMap<>(r);
            s.remove("evidence");
            return s;
        }).toList();
        System.out.println(Json.write(summary, true));
        return results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("accepted"))) ? 0 : 1;
    }

    static Map.Entry<String, Object> runCase(Case c, Path output, Path jar, String java, double timeout)
            throws IOException {
        Path model = c.model();
        Path config = c.config();
        if (c.trace() != null) {
            try {
                TraceCheck.Compiled compiled = TraceCheck.compile(c.model(), c.trace(), output.resolve(c.name() + ".trace"));
                model = compiled.model();
                config = compiled.config();
            } catch (TraceRejected e) {
                return Map.<String, Object>entry("invalid", Map.of("rejected", e.getMessage()));
            }
        }
        Map<String, Object> receipt = Tlc.run(new Tlc.Request(model, config, output.resolve(c.name()), jar, java,
            timeout, 2, "1g", c.trace() != null));
        return Map.<String, Object>entry((String) receipt.get("result"), receipt);
    }

    static List<Case> loadCases(Path manifest) throws IOException {
        Object entries;
        try {
            entries = Json.parse(Files.readString(manifest));
        } catch (Json.SyntaxError e) {
            throw new ProbatioException("Regression manifest is not valid JSON: " + e.getMessage());
        }
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            throw new ProbatioException("Regression manifest must contain cases");
        }
        List<Case> cases = new ArrayList<>();
        for (Object entry : list) {
            cases.add(parseCase(entry, manifest.getParent()));
        }
        if (cases.stream().map(Case::name).distinct().count() != cases.size()) {
            throw new ProbatioException("Case names must be unique");
        }
        return cases;
    }

    static Case parseCase(Object entry, Path base) {
        if (!(entry instanceof Map<?, ?> map) || !map.values().stream().allMatch(v -> v instanceof String)) {
            throw new ProbatioException("Case must be an object of strings: " + Json.write(entry, false));
        }
        Set<String> keys = map.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
        long sources = keys.stream().filter(SOURCE_KEYS::contains).count();
        boolean known = keys.stream().allMatch(k -> REQUIRED_KEYS.contains(k) || SOURCE_KEYS.contains(k));
        if (!keys.containsAll(REQUIRED_KEYS) || !known || sources != 1) {
            throw new ProbatioException("Case needs name, model, expected and exactly one of config/trace: " + keys);
        }
        String name = (String) map.get("name");
        String expected = (String) map.get("expected");
        if (!CASE_NAME.matcher(name).matches()) {
            throw new ProbatioException("Case name must use only ASCII letters, digits, - and _: " + name);
        }
        if (!EXPECTABLE.contains(expected)) {
            throw new ProbatioException("Expected result must be one of " + new TreeSet<>(EXPECTABLE) + ": " + expected);
        }
        return new Case(name, base.resolve((String) map.get("model")), expected,
            optionalPath(base, map.get("config")), optionalPath(base, map.get("trace")));
    }

    static Path optionalPath(Path base, Object value) {
        return value == null ? null : base.resolve((String) value);
    }
}

/** Command-line options: positional arguments, {@code --name value} options and {@code --flag} switches. */
final class Args {
    final List<String> positional = new ArrayList<>();
    final Map<String, String> options = new LinkedHashMap<>();
    final Set<String> flags = new LinkedHashSet<>();

    static Args parse(String[] argv, int positionalCount, Set<String> valueOptions, Set<String> flagOptions) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            String name = arg.startsWith("--") ? arg.substring(2) : null;
            if (name == null) {
                a.positional.add(arg);
            } else if (flagOptions.contains(name)) {
                a.flags.add(name);
            } else if (valueOptions.contains(name) && i + 1 < argv.length) {
                a.options.put(name, argv[++i]);
            } else {
                throw new ProbatioException("Unknown or incomplete option: " + arg + "\n" + Probatio.USAGE);
            }
        }
        if (a.positional.size() != positionalCount) {
            throw new ProbatioException("Expected " + positionalCount + " positional argument(s)\n" + Probatio.USAGE);
        }
        return a;
    }

    Path path(int index) {
        return Path.of(positional.get(index));
    }

    Path requiredPath(String name) {
        return Path.of(Optional.ofNullable(options.get(name))
            .orElseThrow(() -> new ProbatioException("--" + name + " is required")));
    }

    String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    double number(String name, double fallback) {
        try {
            double value = options.containsKey(name) ? Double.parseDouble(options.get(name)) : fallback;
            if (Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Reported below with the same message as a non-finite value.
        }
        throw new ProbatioException("--" + name + " must be a finite number");
    }

    int integer(String name, int fallback) {
        try {
            return options.containsKey(name) ? Integer.parseInt(options.get(name)) : fallback;
        } catch (NumberFormatException e) {
            throw new ProbatioException("--" + name + " must be an integer");
        }
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    Path jar() {
        if (options.containsKey("jar")) {
            return Path.of(options.get("jar"));
        }
        String home = Optional.ofNullable(System.getenv("PROBATIO_HOME")).filter(s -> !s.isEmpty())
            .orElse(Path.of(System.getProperty("user.home"), ".probatio").toString());
        return Path.of(home, "tla2tools.jar");
    }

    String java() {
        return options.getOrDefault("java", ProcessHandle.current().info().command().orElse("java"));
    }
}

/** Strict JSON for traces and manifests: objects keep key order; integers become Long or BigInteger. */
final class Json {
    static final int MAX_DEPTH = 256;

    static final class SyntaxError extends Exception {
        SyntaxError(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) throws SyntaxError {
        Json parser = new Json(text);
        Object value = parser.value(0);
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    private Object value(int depth) throws SyntaxError {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        skipSpace();
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> keyword("true", Boolean.TRUE);
            case 'f' -> keyword("false", Boolean.FALSE);
            case 'n' -> keyword("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws SyntaxError {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (consume('}')) {
            return map;
        }
        do {
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected object key");
            }
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value(depth + 1));
            skipSpace();
        } while (consume(','));
        expect('}');
        return map;
    }

    private List<Object> array(int depth) throws SyntaxError {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (consume(']')) {
            return list;
        }
        do {
            list.add(value(depth + 1));
            skipSpace();
        } while (consume(','));
        expect(']');
        return list;
    }

    private String string() throws SyntaxError {
        StringBuilder out = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            out.append(c == '\\' ? escape() : c);
        }
        throw error("unterminated string");
    }

    private char escape() throws SyntaxError {
        if (pos >= text.length()) {
            throw error("unterminated escape");
        }
        char c = text.charAt(pos++);
        return switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                if (pos + 4 > text.length() || !HEX4.matcher(text.substring(pos, pos + 4)).matches()) {
                    throw error("bad unicode escape");
                }
                pos += 4;
                yield (char) Integer.parseInt(text.substring(pos - 4, pos), 16);
            }
            default -> throw error("bad escape");
        };
    }

    private static final Pattern HEX4 = Pattern.compile("[0-9a-fA-F]{4}");
    private static final Pattern NUMBER =Pattern.compile("-?(?:0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private Object number() throws SyntaxError {
        Matcher m = NUMBER.matcher(text).region(pos, text.length());
        if (!m.lookingAt()) {
            throw error("unexpected character '" + text.charAt(pos) + "'");
        }
        pos = m.end();
        if (m.group(1) != null || m.group(2) != null) {
            return Double.valueOf(m.group());
        }
        BigInteger n = new BigInteger(m.group());
        return n.bitLength() < 64 ? (Object) n.longValue() : n;
    }

    private Object keyword(String word, Object value) throws SyntaxError {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected token");
        }
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private boolean consume(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) throws SyntaxError {
        if (!consume(c)) {
            throw error("expected '" + c + "'");
        }
    }

    private SyntaxError error(String message) {
        return new SyntaxError(message + " at character " + (pos + 1));
    }

    static String write(Object value, boolean pretty) {
        StringBuilder out = new StringBuilder();
        write(out, value, pretty ? "\n" : null);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, String indent) {
        String inner = indent == null ? null : indent + "  ";
        if (value instanceof Map<?, ?> map) {
            container(out, map.entrySet(), '{', '}', inner, indent, (e, o) -> {
                writeString(o, String.valueOf(e.getKey()));
                o.append(": ");
                write(o, e.getValue(), inner);
            });
        } else if (value instanceof List<?> list) {
            container(out, list, '[', ']', inner, indent, (v, o) -> write(o, v, inner));
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Path p) {
            writeString(out, p.toString());
        } else {
            out.append(value);
        }
    }

    private interface ItemWriter<T> {
        void write(T item, StringBuilder out);
    }

    private static <T> void container(StringBuilder out, Iterable<T> items, char open, char close,
                                      String inner, String indent, ItemWriter<T> writer) {
        out.append(open);
        boolean first = true;
        for (T item : items) {
            out.append(first ? "" : ",");
            if (inner != null) {
                out.append(inner);
            } else if (!first) {
                out.append(' ');
            }
            writer.write(item, out);
            first = false;
        }
        if (!first && indent != null) {
            out.append(indent);
        }
        out.append(close);
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
