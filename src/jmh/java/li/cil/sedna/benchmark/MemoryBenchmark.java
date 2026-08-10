package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(MemoryBenchmark.INSTRUCTIONS)
public class MemoryBenchmark {
    static final int INSTRUCTIONS = 200_000;

    private static final int RAM_SIZE = 48 * 1024 * 1024;
    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;

    private static final int NODE_OFFSET = 64;

    @Param({"16", "256", "512", "1024", "2048", "8192", "16384"})
    public int workingSetKiB;

    @Param({"bare", "sv39"})
    public String translation;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;
    private long chainHead;

    @Setup(Level.Trial)
    public void setUp() {
        final boolean paged = "sv39".equals(translation);
        vm = paged ? Vm.paged(RAM_SIZE) : Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        codeStart = vm.usableStart();
        final long dataStart = align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);

        chainHead = buildPointerChain(dataStart, workingSetKiB * 1024L);

        // x1 walks the chain: every `ld x1, 0(x1)` lands on a different page.
        vm.fill(codeStart, CODE_SIZE, R5Assembler.ld(1, 1, 0));

        if (paged) {
            vm.enterSupervisor(codeStart);
        }
    }

    /**
     * Links one node per page into a single cycle, visiting the pages in a strided order so that
     * consecutive accesses are never to adjacent pages.
     *
     * @return the address of the node to start from.
     */
    private long buildPointerChain(final long start, final long workingSetBytes) {
        final int nodeCount = Math.max(1, (int) (workingSetBytes / Vm.PAGE_SIZE));

        final int stride = Math.max(1, nodeCount / 3) | 1;

        long current = start + NODE_OFFSET;
        int index = 0;
        for (int i = 0; i < nodeCount; i++) {
            final int next = (index + stride) % nodeCount;
            final long nextAddress = start + (long) next * Vm.PAGE_SIZE + NODE_OFFSET;
            vm.store64(current, nextAddress);
            current = nextAddress;
            index = next;
        }

        return start + NODE_OFFSET;
    }

    @Benchmark
    public long chase() {
        vm.registers()[1] = chainHead;
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }

    private static long align(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
