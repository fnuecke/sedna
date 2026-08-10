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
        final long dataStart = Vm.align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);

        chainHead = vm.buildPointerChain(dataStart, nodeCount(workingSetKiB * 1024L));

        // x1 walks the chain: every `ld x1, 0(x1)` lands on a different page.
        vm.fill(codeStart, CODE_SIZE, R5Assembler.ld(1, 1, 0));

        if (paged) {
            vm.enterSupervisor(codeStart);
        }
    }

    private static int nodeCount(final long workingSetBytes) {
        return Math.max(1, (int) (workingSetBytes / Vm.PAGE_SIZE));
    }

    @Benchmark
    public long chase() {
        vm.registers()[1] = chainHead;
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }

}
