package li.cil.sedna.z80;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import li.cil.ceres.json.JsonSerialization;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the SingleStepTests vectors from the submodule at {@code src/test/data/z80/singlesteptests}.
 * <p>
 * Known deviations tolerated by the comparison:
 * <ul>
 * <li>A DD/FD prefix in front of an opcode it does not modify executes 4T faster than real
 * hardware (the prefixed variants share the unprefixed timing).</li>
 * <li>SCF/CCF X/Y flags are computed from A alone; real hardware mixes in the Q register, which
 * this implementation does not model. F bits 3/5 are masked for those two opcodes.</li>
 * </ul>
 */
public final class Z80SingleStepTests {
    private static final Path ROOT = Path.of("src/test/data/z80/singlesteptests/v1");
    private static final int MAX_REPORTED_MISMATCHES = 5;

    @TestFactory
    public Collection<DynamicTest> singleStepTests() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(ROOT),
                "SingleStepTests submodule not checked out, skipping. Run `git submodule update --init` to run these tests.");

        final ArrayList<DynamicTest> tests = new ArrayList<>();
        try (final Stream<Path> files = Files.list(ROOT)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> tests.add(DynamicTest.dynamicTest(p.getFileName().toString(), () -> runFile(p))));
        }
        return tests;
    }

    private static void runFile(final Path file) throws IOException, MemoryAccessException {
        final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        final SimpleMemoryMap ioMap = new SimpleMemoryMap();
        final PhysicalMemory memory = Memory.create(0x10000);
        memoryMap.addDevice(0, memory);

        final Z80CPUBase cpu = (Z80CPUBase) Z80CPU.create(memoryMap, ioMap);

        final List<int[]> expectedPortWrites = new ArrayList<>();
        final List<int[]> actualPortWrites = new ArrayList<>();
        final List<int[]> portReads = new ArrayList<>();
        final int[] portReadIndex = new int[1];
        ioMap.addDevice(0, new MemoryMappedDevice() {
            @Override
            public int getLength() {
                return 0x10000;
            }

            @Override
            public int getSupportedSizes() {
                return 1 << Sizes.SIZE_8_LOG2;
            }

            @Override
            public long load(final int offset, final int sizeLog2) {
                if (portReadIndex[0] < portReads.size()) {
                    final int[] entry = portReads.get(portReadIndex[0]++);
                    if (entry[0] != offset) {
                        fail(String.format("port read: expected port %04x, was %04x", entry[0], offset));
                    }
                    return entry[1];
                }
                return 0xFF;
            }

            @Override
            public void store(final int offset, final long value, final int sizeLog2) {
                actualPortWrites.add(new int[]{offset, (int) value});
            }
        });

        final JsonArray cases;
        try (final Reader reader = Files.newBufferedReader(file)) {
            cases = JsonParser.parseReader(reader).getAsJsonArray();
        }

        final List<String> mismatches = new ArrayList<>();
        int failedCases = 0;
        for (final JsonElement element : cases) {
            final JsonObject testCase = element.getAsJsonObject();
            final JsonObject initial = testCase.getAsJsonObject("initial");
            final JsonObject expected = testCase.getAsJsonObject("final");

            // Reset memory touched by the previous case, then apply this case's cells.
            expectedPortWrites.clear();
            actualPortWrites.clear();
            portReads.clear();
            portReadIndex[0] = 0;
            if (testCase.has("ports")) {
                for (final JsonElement port : testCase.getAsJsonArray("ports")) {
                    final JsonArray entry = port.getAsJsonArray();
                    final int[] value = {entry.get(0).getAsInt(), entry.get(1).getAsInt()};
                    if ("r".equals(entry.get(2).getAsString())) {
                        portReads.add(value);
                    } else {
                        expectedPortWrites.add(value);
                    }
                }
            }

            JsonSerialization.deserialize(toCeresState(initial), Z80CPUBase.class, cpu);

            // step() consumes the EI delay before executing the following instruction.
            cpu.eiDelay = false;

            final List<int[]> ram = readRam(initial);
            final List<int[]> expectedRam = readRam(expected);
            for (final int[] cell : ram) {
                memory.store(cell[0], cell[1], Sizes.SIZE_8_LOG2);
            }

            final long cyclesBefore = cpu.cycles;
            cpu.interpretTrace(true, null);
            final long cyclesTaken = cpu.cycles - cyclesBefore;

            final List<String> caseMismatches = new ArrayList<>();
            compareState(cpu, toCeresState(expected), caseMismatches, testCase.get("name").getAsString());
            for (final int[] cell : expectedRam) {
                final int actual = (int) memory.load(cell[0], Sizes.SIZE_8_LOG2) & 0xFF;
                if (actual != cell[1]) {
                    caseMismatches.add(String.format("ram[%04x]: expected %02x, was %02x", cell[0], cell[1], actual));
                }
            }

            final int expectedCycles = testCase.getAsJsonArray("cycles").size();
            // Pass-through opcodes behind a DD/FD prefix are missing the prefix's 4T; everything
            // else must match exactly.
            final String fileName = file.getFileName().toString();
            final boolean prefixed = fileName.startsWith("dd ") || fileName.startsWith("fd ");
            if (cyclesTaken != expectedCycles && !(prefixed && cyclesTaken + 4 == expectedCycles)) {
                caseMismatches.add(String.format("cycles: expected %d, was %d", expectedCycles, cyclesTaken));
            }

            if (expectedPortWrites.size() != actualPortWrites.size()) {
                caseMismatches.add(String.format("port writes: expected %d, was %d", expectedPortWrites.size(), actualPortWrites.size()));
            } else {
                for (int i = 0; i < expectedPortWrites.size(); i++) {
                    final int[] want = expectedPortWrites.get(i);
                    final int[] got = actualPortWrites.get(i);
                    if (want[0] != got[0] || want[1] != got[1]) {
                        caseMismatches.add(String.format("port write %d: expected %02x->%04x, was %02x->%04x", i, want[1], want[0], got[1], got[0]));
                    }
                }
            }

            if (!caseMismatches.isEmpty()) {
                failedCases++;
                if (mismatches.size() < MAX_REPORTED_MISMATCHES) {
                    mismatches.add("case [" + testCase.get("name").getAsString() + "]: " + String.join("; ", caseMismatches));
                }
            }

            // Clear for the next one.
            for (final int[] cell : ram) {
                memory.store(cell[0], 0, Sizes.SIZE_8_LOG2);
            }
            for (final int[] cell : expectedRam) {
                memory.store(cell[0], 0, Sizes.SIZE_8_LOG2);
            }
        }

        if (failedCases > 0) {
            fail(failedCases + "/" + cases.size() + " cases failed, first mismatches:\n" + String.join("\n", mismatches));
        }
    }

    private static List<int[]> readRam(final JsonObject state) {
        final List<int[]> result = new ArrayList<>();
        for (final JsonElement cell : state.getAsJsonArray("ram")) {
            final JsonArray entry = cell.getAsJsonArray();
            result.add(new int[]{entry.get(0).getAsInt(), entry.get(1).getAsInt()});
        }
        return result;
    }

    /**
     * Maps a SingleStepTests state object onto the CPU's own serialization format. Fields not
     * part of the vectors' state (cycles, cycleDebt) are omitted and keep their current value
     * when deserialized in place.
     */
    private static JsonObject toCeresState(final JsonObject state) {
        final JsonObject result = new JsonObject();

        final JsonArray r = new JsonArray(8);
        r.add(state.get("b").getAsInt());
        r.add(state.get("c").getAsInt());
        r.add(state.get("d").getAsInt());
        r.add(state.get("e").getAsInt());
        r.add(state.get("h").getAsInt());
        r.add(state.get("l").getAsInt());
        r.add(0);
        r.add(state.get("a").getAsInt());
        result.add("r", r);

        result.addProperty("f", state.get("f").getAsInt());

        final JsonArray r2 = new JsonArray(8);
        final int bc2 = state.get("bc_").getAsInt();
        final int de2 = state.get("de_").getAsInt();
        final int hl2 = state.get("hl_").getAsInt();
        final int af2 = state.get("af_").getAsInt();
        r2.add(bc2 >>> 8);
        r2.add(bc2 & 0xFF);
        r2.add(de2 >>> 8);
        r2.add(de2 & 0xFF);
        r2.add(hl2 >>> 8);
        r2.add(hl2 & 0xFF);
        r2.add(0);
        r2.add(af2 >>> 8);
        result.add("r2", r2);

        result.addProperty("f2", af2 & 0xFF);

        final JsonArray ixiy = new JsonArray(2);
        ixiy.add(state.get("ix").getAsInt());
        ixiy.add(state.get("iy").getAsInt());
        result.add("ixiy", ixiy);

        result.addProperty("sp", state.get("sp").getAsInt());
        result.addProperty("pc", state.get("pc").getAsLong());
        result.addProperty("i", state.get("i").getAsInt());
        result.addProperty("rr", state.get("r").getAsInt());
        result.addProperty("iff1", state.get("iff1").getAsInt() != 0);
        result.addProperty("iff2", state.get("iff2").getAsInt() != 0);
        result.addProperty("im", state.get("im").getAsInt());
        result.addProperty("memptr", state.get("wz").getAsInt());
        result.addProperty("halted", false);
        result.addProperty("eiDelay", state.get("ei").getAsInt() != 0);

        result.addProperty("irqRequested", false);
        result.addProperty("irqData", 0);
        result.addProperty("nmiRequested", false);

        return result;
    }

    private static void compareState(final Z80CPUBase cpu, final JsonObject expected, final List<String> mismatches, final String name) {
        final JsonObject actual = JsonSerialization.serialize(cpu, Z80CPUBase.class);
        actual.addProperty("pc", actual.get("pc").getAsLong() & 0xFFFF);

        // SCF/CCF X/Y depend on the unmodeled Q register; prefixes do not change that.
        final String[] tokens = name.split(" ");
        int opcodeIndex = 0;
        while (opcodeIndex < tokens.length - 2 && ("dd".equalsIgnoreCase(tokens[opcodeIndex]) || "fd".equalsIgnoreCase(tokens[opcodeIndex]))) {
            opcodeIndex++;
        }
        final int opcode = Integer.parseInt(tokens[opcodeIndex], 16);
        if (opcode == 0x37 || opcode == 0x3F) {
            expected.addProperty("f", expected.get("f").getAsInt() & ~0x28);
            actual.addProperty("f", actual.get("f").getAsInt() & ~0x28);
        }

        // The vectors have no halted field; only HALT leaves the CPU halted.
        expected.addProperty("halted", opcode == 0x76);

        for (final Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            final JsonElement actualValue = actual.get(entry.getKey());
            if (!entry.getValue().equals(actualValue)) {
                mismatches.add(String.format("%s: expected %s, was %s", entry.getKey(), entry.getValue(), actualValue));
            }
        }
    }
}
