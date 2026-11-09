import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for Probatio.java without a test framework. Compile and run from the repository root:
 * <pre>
 * javac -d build/test scripts/Probatio.java examples/lease/LeaseExample.java tests/ProbatioTest.java
 * TLA_JAR=/path/to/tla2tools.jar java -cp build/test ProbatioTest
 * </pre>
 * Without TLA_JAR the tests that run TLC are skipped.
 */
public class ProbatioTest {
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path LEASE_MODEL = ROOT.resolve("examples/lease/Lease.tla");
    static final Path LEASE_FIXED_CFG = ROOT.resolve("examples/lease/Fixed.cfg");
    static final String JAR = System.getenv("TLA_JAR");
    static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    interface Body {
        void run(Path tmp) throws Exception;
    }

    record Test(String name, boolean needsTlc, Body body) {}

    public static void main(String[] args) throws Exception {
        List<Test> tests = List.of(
            new Test("commented-out or empty property sections do not count", false, ProbatioTest::configGuard),
            new Test("comment stripping keeps live text", false, ProbatioTest::commentStripping),
            new Test("trace rejects bad sequences and missing state", false, ProbatioTest::badSequences),
            new Test("malformed, empty and deeply nested traces are rejections", false, ProbatioTest::malformedTraces),
            new Test("TLA literals and their rejections", false, ProbatioTest::literals),
            new Test("JSON parser is strict", false, ProbatioTest::strictJson),
            new Test("negative integers compile with the Integers module", false, ProbatioTest::negativeCompile),
            new Test("model must define the trace contract", false, ProbatioTest::modelContract),
            new Test("reduced causal order implies exactly the full order", false, ProbatioTest::reducedOrder),
            new Test("sequential trace order is linear in size", false, ProbatioTest::linearOrder),
            new Test("missing trace file is an error, not an accepted control", false, ProbatioTest::missingTrace),
            new Test("rejected trace is invalid", false, ProbatioTest::rejectedTrace),
            new Test("malformed manifests fail before any case runs", false, ProbatioTest::malformedManifests),
            new Test("exit codes separate accepted, rejected and unchecked", false, ProbatioTest::exitCodes),
            new Test("init stamps the source state and never overwrites", false, ProbatioTest::initWorkspace),
            new Test("init outside Git records the gap", false, ProbatioTest::initWithoutGit),
            new Test("doctor names the fix for a missing jar", false, ProbatioTest::doctorMissingJar),
            new Test("doctor accepts a working toolchain", true, ProbatioTest::doctorWorking),
            new Test("partial-order search accepts one valid branch", true, ProbatioTest::partialOrder),
            new Test("negative observed integers parse in SANY", true, ProbatioTest::negativeParse),
            new Test("no false pass on timeout or parse error", true, ProbatioTest::timeoutAndParseError),
            new Test("deadlock and liveness are not tool errors", true, ProbatioTest::deadlockAndLiveness),
            new Test("empty initial state space is not a pass", true, ProbatioTest::emptyInit),
            new Test("joint regression records failure and preserves inputs", true, ProbatioTest::jointRegression),
            new Test("examples pass their own controls", true, ProbatioTest::examples));
        int failed = 0;
        int skipped = 0;
        for (Test test : tests) {
            if (test.needsTlc() && JAR == null) {
                System.out.println("skip  " + test.name() + " (TLA_JAR not set)");
                skipped++;
                continue;
            }
            Path tmp = Files.createTempDirectory("probatio-test");
            try {
                test.body().run(tmp);
                System.out.println("ok    " + test.name());
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL  " + test.name() + ": " + e);
            } finally {
                deleteTree(tmp);
            }
        }
        System.out.printf("%d tests, %d failed, %d skipped%n", tests.size(), failed, skipped);
        System.exit(failed == 0 ? 0 : 1);
    }

    // Input validation: no Java subprocess or TLC needed.

    static void configGuard(Path tmp) throws Exception {
        Path jar = placeholderJar(tmp);
        Files.writeString(tmp.resolve("M.tla"), "---- MODULE M ----\n====\n");
        List<String> configs = List.of(
            "INIT Init\nNEXT Next\n\\* INVARIANT Safe\n",
            "INIT Init\nNEXT Next\n(* INVARIANT Safe *)\n",
            "INIT Init\nNEXT Next\n(* outer (* inner *) INVARIANT Safe *)\n",
            "INIT Init\nNEXT Next\nINVARIANT\n",
            "INIT Init\nINVARIANT\nNEXT Next\n");
        for (String config : configs) {
            Files.writeString(tmp.resolve("M.cfg"), config);
            expectThrows(ProbatioException.class, "No correctness property", () -> Tlc.run(new Tlc.Request(
                tmp.resolve("M.tla"), tmp.resolve("M.cfg"), tmp.resolve("out"), jar, JAVA, 120, 2, "1g", false)));
        }
    }

    static void commentStripping(Path tmp) {
        assertEquals("A  B \nC", Tlc.stripComments("A (* x (* y *) z *) B \\* c\nC"));
    }

    static void badSequences(Path tmp) throws Exception {
        Path trace = tmp.resolve("raw.ndjson");
        LeaseExample.scenario(trace, true);
        List<Map<String, Object>> rows = readRows(trace);
        rows.get(rows.size() - 1).put("seq", 99L);
        expectThrows(TraceRejected.class, "contiguous", () -> TraceCheck.readEvents(writeRows(tmp.resolve("gap.ndjson"), rows)));
        rows.get(rows.size() - 1).put("seq", 2L);
        rows.get(rows.size() - 1).put("state", new LinkedHashMap<>());
        expectThrows(TraceRejected.class, "observed state", () -> TraceCheck.readEvents(writeRows(tmp.resolve("nostate.ndjson"), rows)));
    }

    static void malformedTraces(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bad.ndjson"), "{");
        Files.writeString(tmp.resolve("empty.ndjson"), "\n");
        Files.writeString(tmp.resolve("deep.ndjson"), "{\"data\": " + "[".repeat(100_000) + "]".repeat(100_000) + "}\n");
        Files.write(tmp.resolve("latin1.ndjson"), new byte[] {'"', 'o', 'l', (byte) 0xe1, '"', '\n'});
        for (String name : List.of("bad.ndjson", "empty.ndjson", "deep.ndjson", "latin1.ndjson")) {
            expectThrows(TraceRejected.class, "", () -> TraceCheck.readEvents(tmp.resolve(name)));
        }
    }

    static void literals(Path tmp) throws Exception {
        assertEquals("[n |-> -5, ok |-> TRUE, s |-> <<1, \"a\\\"b\">>]",
            TraceCheck.literal(Json.parse("{\"n\": -5, \"ok\": true, \"s\": [1, \"a\\\"b\"]}")));
        for (String json : List.of("{\"IF\": 1}", "{\"WF_x\": 1}", "{}", "1.5", "1099511627776", "-2147483648",
                                   "\"ol\\u00e1\"", "\"\\u007f\"", "null", "99999999999999999999")) {
            expectThrows(TraceRejected.class, "", () -> TraceCheck.literal(Json.parse(json)));
        }
    }

    static void strictJson(Path tmp) throws Exception {
        assertEquals(Map.of("a", List.of(1L, "\u00e9", true)), Json.parse(" {\"a\": [1, \"\\u00e9\", true]} "));
        for (String bad : List.of("{\"a\": 01}", "\"\\u+1a2\"", "[1,]", "{\"a\" 1}", "tru", "NaN", "[1] x", "\"a\nb\"")) {
            expectThrows(Json.SyntaxError.class, "", () -> Json.parse(bad));
        }
        Object nested = Json.parse("{\"k\": [1, {\"x\": \"q\\\"\"}], \"e\": {}}");
        assertEquals(nested, Json.parse(Json.write(nested, true)));
        assertEquals(nested, Json.parse(Json.write(nested, false)));
    }

    static void negativeCompile(Path tmp) throws Exception {
        Path trace = writeRows(tmp.resolve("neg.ndjson"), List.of(event("A", 1, 1, 2, Map.of("delta", -1L))));
        TraceCheck.compile(LEASE_MODEL, trace, tmp.resolve("compiled"));
        assertTrue(Files.readString(tmp.resolve("compiled/TraceInput.tla")).contains("EXTENDS Integers"), "extends Integers");
    }

    static void modelContract(Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("model"));
        Files.writeString(tmp.resolve("model/Bare.tla"), "---- MODULE Bare ----\nInit == TRUE\n====\n");
        Path trace = writeRows(tmp.resolve("t.ndjson"), List.of(event("A", 1, 1, 2, null)));
        expectThrows(ProbatioException.class, "Step(e), Observe",
            () -> TraceCheck.compile(tmp.resolve("model/Bare.tla"), trace, tmp.resolve("out")));
    }

    static void reducedOrder(Path tmp) {
        Random rng = new Random(7);
        for (int round = 0; round < 300; round++) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (String actor : List.of("A", "B", "C")) {
                long clock = rng.nextInt(6);
                int count = 1 + rng.nextInt(6);
                for (int seq = 1; seq <= count; seq++) {
                    long start = clock + rng.nextInt(4);
                    clock = start + rng.nextInt(5);
                    events.add(event(actor, seq, start, clock, null));
                }
            }
            java.util.Collections.shuffle(events, rng);
            assertEquals(fullOrder(events), closure(TraceCheck.immediatePredecessors(events)));
        }
    }

    static void linearOrder(Path tmp) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            events.add(event("A", i, 2L * i, 2L * i + 1, null));
        }
        assertEquals(499, TraceCheck.immediatePredecessors(events).stream().mapToInt(Set::size).sum());
    }

    static void missingTrace(Path tmp) throws Exception {
        Path manifest = manifest(tmp, List.of(traceCase("ctl", "TYPO.ndjson", "invalid")));
        Map<String, Object> result = Regress.evaluate(manifest, tmp.resolve("out"), placeholderJar(tmp), JAVA, 120).get(0);
        assertEquals("error", result.get("actual"));
        assertEquals(false, result.get("accepted"));
    }

    static void rejectedTrace(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bad.ndjson"), "{");
        Path manifest = manifest(tmp, List.of(traceCase("ctl", "bad.ndjson", "invalid")));
        assertEquals(true, Regress.evaluate(manifest, tmp.resolve("out"), placeholderJar(tmp), JAVA, 120).get(0).get("accepted"));
    }

    static void malformedManifests(Path tmp) throws Exception {
        Map<String, Object> ok = Map.of("name", "a", "model", "M.tla", "config", "M.cfg", "expected", "pass");
        List<Object> broken = List.of(
            List.of(),
            List.of(Map.of("name", "a", "expected", "pass", "config", "M.cfg")),
            List.of(with(ok, "trace", "t.ndjson")),
            List.of(with(ok, "expected", "error")),
            List.of(with(ok, "name", "café")),
            List.of(with(ok, "expect", "pass")),
            List.of(with(ok, "model", 3L)),
            List.of(ok, ok),
            "not a list");
        for (int i = 0; i < broken.size(); i++) {
            Path manifest = tmp.resolve("suite" + i + ".json");
            Files.writeString(manifest, Json.write(broken.get(i), false));
            Path out = tmp.resolve("out" + i);
            expectThrows(ProbatioException.class, "", () -> Regress.evaluate(manifest, out, placeholderJar(tmp), JAVA, 120));
            assertTrue(!Files.exists(out), "no output directory for a rejected manifest");
        }
    }

    static void exitCodes(Path tmp) throws Exception {
        Map<String, Integer> expected = Map.of("pass", 0, "match", 0, "violation", 1, "deadlock", 1,
            "mismatch", 1, "error", 2, "timeout", 2);
        expected.forEach((result, code) -> assertEquals(code, Probatio.exitCode(result), result));
        Path manifest = manifest(tmp, List.of(traceCase("ctl", "TYPO.ndjson", "invalid")));
        List<Map<String, Object>> errored = Regress.evaluate(manifest, tmp.resolve("out"), placeholderJar(tmp), JAVA, 120);
        assertEquals(2, Regress.report(errored), "a case that never ran is not a rejection");
        Files.writeString(tmp.resolve("bad.ndjson"), "{");
        Path rejected = manifest(tmp, List.of(traceCase("ctl", "bad.ndjson", "match")));
        assertEquals(1, Regress.report(Regress.evaluate(rejected, tmp.resolve("out2"), placeholderJar(tmp), JAVA, 120)));
    }

    static void initWorkspace(Path tmp) throws Exception {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        git(repo, "init", "-q");
        Files.write(repo.resolve("a.txt"), "ola\n".getBytes());
        git(repo, "add", "a.txt");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");
        Files.write(repo.resolve("a.txt"), new byte[] {'o', 'l', (byte) 0xe1, '\n'});
        Files.writeString(repo.resolve("café.txt"), "new");
        Path templates = ROOT.resolve("assets/workspace");
        Map<String, Object> summary = Workspace.init(repo, tmp.resolve("ws"), "cost $timestamp and 100%", templates);
        String scope = Files.readString(tmp.resolve("ws/scope.md"));
        assertTrue(scope.contains("cost $timestamp and 100%"), "placeholder-like scope text stays literal");
        assertTrue(!scope.contains("$revision_text") && scope.contains("untracked files: 1"), "fields filled: " + scope);
        for (String name : List.of("findings", "checks", "harness", "traces", "controls", "models/modeling.md", "report.md")) {
            assertTrue(Files.exists(tmp.resolve("ws").resolve(name)), name);
        }
        Files.writeString(repo.resolve("café.txt"), "changed");
        Workspace.init(repo, tmp.resolve("ws2"), "x", templates);
        assertTrue(!Files.readString(tmp.resolve("ws2/scope.md")).contains(digestOf((String) summary.get("working_tree"))),
            "untracked content with a non-ASCII name is hashed");
        expectThrows(ProbatioException.class, "already exists", () -> Workspace.init(repo, tmp.resolve("ws"), "x", templates));
    }

    static void initWithoutGit(Path tmp) throws Exception {
        Path plain = Files.createDirectory(tmp.resolve("plain"));
        Map<String, Object> summary = Workspace.init(plain, tmp.resolve("ws"), "x", ROOT.resolve("assets/workspace"));
        assertTrue(String.valueOf(summary.get("working_tree")).startsWith("not available"), "gap recorded: " + summary);
    }

    static void doctorMissingJar(Path tmp) throws Exception {
        List<Doctor.Check> checks = Doctor.run(tmp.resolve("absent.jar"), JAVA, ROOT.resolve("scripts/toolchain.env"));
        Doctor.Check jar = checks.stream().filter(c -> c.name().equals("jar")).findFirst().orElseThrow();
        assertTrue(!jar.ok() && jar.fix().contains("install.sh"), "jar check points at the installer: " + jar);
        assertEquals(2, Doctor.report(checks));
    }

    static void doctorWorking(Path tmp) throws Exception {
        List<Doctor.Check> checks = Doctor.run(Path.of(JAR), JAVA, ROOT.resolve("scripts/toolchain.env"));
        assertEquals(0, Doctor.report(checks), "checks: " + checks);
    }

    // End-to-end checks that run TLC.

    static void partialOrder(Path tmp) throws Exception {
        // Synthetic validator control, not a trace claimed as implementation evidence.
        List<Map<String, Object>> events = new ArrayList<>(List.of(
            event("B", 1, 1, 8, null), event("A", 1, 2, 3, null),
            leaseEvent("A", 2, 4, 5, "release", "")));
        assertEquals("match", checkTrace(tmp, events, "valid"));
        state(events.get(0)).put("owner", "C");
        assertEquals("mismatch", checkTrace(tmp, events, "impossible"));
    }

    static void negativeParse(Path tmp) throws Exception {
        assertEquals("match", checkTrace(tmp, List.of(event("A", 1, 1, 2, Map.of("delta", -1L))), "negative"));
    }

    static void timeoutAndParseError(Path tmp) throws Exception {
        assertEquals("timeout", tlc(LEASE_MODEL, LEASE_FIXED_CFG, tmp.resolve("timeout"), 0.000001).get("result"));
        Path[] broken = writeModel(tmp, "Broken", "THIS IS NOT VALID", "INIT Init\nNEXT Next\nINVARIANT Safe\n");
        assertEquals("error", tlc(broken[0], broken[1], tmp.resolve("parse"), 120).get("result"));
    }

    static void deadlockAndLiveness(Path tmp) throws Exception {
        String body = "EXTENDS Naturals\nVARIABLE x\nInit == x=0\nStop == FALSE /\\ x'=x\nLoop == x'=x\nSafe == x=0\nEventually == <>(x=1)";
        Path[] model = writeModel(tmp, "Progress", body, "INIT Init\nNEXT Stop\nINVARIANT Safe\n");
        assertEquals("deadlock", tlc(model[0], model[1], tmp.resolve("deadlock"), 120).get("result"));
        Files.writeString(model[1], "INIT Init\nNEXT Loop\nPROPERTY Eventually\n");
        assertEquals("violation", tlc(model[0], model[1], tmp.resolve("liveness"), 120).get("result"));
    }

    static void emptyInit(Path tmp) throws Exception {
        String body = "EXTENDS Naturals\nVARIABLE x\nInit == x=0 /\\ x=1\nNext == x'=x\nSafe == x=0";
        Path[] model = writeModel(tmp, "Empty", body, "INIT Init\nNEXT Next\nINVARIANT Safe\n");
        assertEquals("error", tlc(model[0], model[1], tmp.resolve("empty-init"), 120).get("result"));
    }

    static void jointRegression(Path tmp) throws Exception {
        Path trace = tmp.resolve("raw.ndjson");
        LeaseExample.scenario(trace, true);
        Map<String, Object> safety = Map.of("name", "safety", "model", LEASE_MODEL.toString(),
            "config", LEASE_FIXED_CFG.toString(), "expected", "pass");
        Path manifest = manifest(tmp, List.of(safety, traceCase("trace", trace.toString(), "match")));
        List<Map<String, Object>> first = Regress.evaluate(manifest, tmp.resolve("round1"), Path.of(JAR), JAVA, 120);
        assertTrue(first.stream().allMatch(r -> Boolean.TRUE.equals(r.get("accepted"))), "first round accepted: " + first);
        Path snapshot = tmp.resolve("round1/trace/inputs/source.ndjson");
        String saved = Files.readString(snapshot);
        List<Map<String, Object>> rows = readRows(trace);
        state(rows.get(rows.size() - 1)).put("owner", "");
        writeRows(trace, rows);
        List<Map<String, Object>> second = Regress.evaluate(manifest, tmp.resolve("round2"), Path.of(JAR), JAVA, 120);
        assertEquals(List.of(true, false), second.stream().map(r -> r.get("accepted")).toList());
        assertEquals("mismatch", second.get(1).get("actual"));
        assertEquals(saved, Files.readString(snapshot));
        expectThrows(java.nio.file.FileAlreadyExistsException.class, "",
            () -> Regress.evaluate(manifest, tmp.resolve("round1"), Path.of(JAR), JAVA, 120));
    }

    static void examples(Path tmp) throws Exception {
        for (String example : List.of("lease/LeaseExample.java", "counter/CounterExample.java")) {
            Process process = new ProcessBuilder(JAVA, ROOT.resolve("examples/" + example).toString(),
                "--out", tmp.resolve(example.replace('/', '-')).toString(), "--jar", JAR)
                .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), example + " output:\n" + output);
        }
    }

    // Helpers.

    static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + output);
    }

    static String digestOf(String workingTree) {
        int at = workingTree.indexOf("content sha256: ");
        return workingTree.substring(at + 16, at + 16 + 64);
    }

    static Map<String, Object> tlc(Path model, Path config, Path out, double timeout) throws Exception {
        return Tlc.run(new Tlc.Request(model, config, out, Path.of(JAR), JAVA, timeout, 2, "1g", false));
    }

    static String checkTrace(Path tmp, List<Map<String, Object>> events, String name) throws Exception {
        Path trace = writeRows(tmp.resolve(name + ".ndjson"), events);
        TraceCheck.Compiled compiled = TraceCheck.compile(LEASE_MODEL, trace, tmp.resolve(name + "-compiled"));
        return (String) Tlc.run(new Tlc.Request(compiled.model(), compiled.config(), tmp.resolve(name),
            Path.of(JAR), JAVA, 120, 2, "1g", true)).get("result");
    }

    static Path[] writeModel(Path tmp, String name, String body, String config) throws Exception {
        Path model = tmp.resolve(name + ".tla");
        Files.writeString(model, "---- MODULE " + name + " ----\n" + body + "\n====\n");
        Path cfg = tmp.resolve(name + ".cfg");
        Files.writeString(cfg, config);
        return new Path[] {model, cfg};
    }

    static Map<String, Object> event(String actor, long seq, long start, long end, Map<String, Object> data) {
        Map<String, Object> event = leaseEvent(actor, seq, start, end, "acquire", actor);
        if (data != null) {
            event.put("data", data);
        }
        return event;
    }

    static Map<String, Object> leaseEvent(String actor, long seq, long start, long end, String action, String owner) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("owner", owner);
        state.put("guarded", true);
        return new LinkedHashMap<>(Map.of("actor", actor, "seq", seq, "start_ns", start, "end_ns", end,
            "action", action, "data", Map.of("key", "x"), "state", state));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> state(Map<String, Object> event) {
        return (Map<String, Object>) event.get("state");
    }

    static Map<String, Object> traceCase(String name, String trace, String expected) {
        return Map.of("name", name, "model", LEASE_MODEL.toString(), "trace", trace, "expected", expected);
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    static Path manifest(Path tmp, List<Map<String, Object>> cases) throws Exception {
        Path path = tmp.resolve("suite.json");
        Files.writeString(path, Json.write(cases, false));
        return path;
    }

    static Path placeholderJar(Path tmp) throws Exception {
        Path jar = tmp.resolve("placeholder.jar");
        Files.writeString(jar, "not a real jar; these tests stop before TLC starts");
        return jar;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readRows(Path path) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            rows.add((Map<String, Object>) Json.parse(line));
        }
        return rows;
    }

    static Path writeRows(Path path, List<Map<String, Object>> rows) throws Exception {
        Files.writeString(path, rows.stream().map(r -> Json.write(r, false)).collect(Collectors.joining("\n", "", "\n")));
        return path;
    }

    static List<Set<Integer>> fullOrder(List<Map<String, Object>> events) {
        List<Set<Integer>> order = new ArrayList<>();
        for (Map<String, Object> e : events) {
            Set<Integer> before = new TreeSet<>();
            for (int j = 0; j < events.size(); j++) {
                Map<String, Object> o = events.get(j);
                boolean sameActor = o.get("actor").equals(e.get("actor")) && (long) o.get("seq") < (long) e.get("seq");
                if (o != e && (sameActor || (long) o.get("end_ns") < (long) e.get("start_ns"))) {
                    before.add(j + 1);
                }
            }
            order.add(before);
        }
        return order;
    }

    /** Transitive closure of 1-based predecessor sets, as a fixed point. */
    static List<Set<Integer>> closure(List<Set<Integer>> direct) {
        List<Set<Integer>> full = direct.stream().<Set<Integer>>map(TreeSet::new).collect(Collectors.toList());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Set<Integer> reach : full) {
                Set<Integer> implied = reach.stream().flatMap(p -> full.get(p - 1).stream()).collect(Collectors.toSet());
                changed |= reach.addAll(implied);
            }
        }
        return full;
    }

    interface Action {
        void run() throws Exception;
    }

    static void expectThrows(Class<? extends Throwable> type, String messagePart, Action action) {
        try {
            action.run();
        } catch (Throwable e) {
            if (type.isInstance(e) && String.valueOf(e.getMessage()).contains(messagePart)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " containing '" + messagePart + "', got " + e, e);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " containing '" + messagePart + "'");
    }

    static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "");
    }

    static void assertEquals(Object expected, Object actual, String context) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual + (context.isEmpty() ? "" : " (" + context + ")"));
        }
    }

    static void assertTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void deleteTree(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
