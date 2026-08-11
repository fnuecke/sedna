package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(MapDensityBenchmark.INSTRUCTIONS)
public class MapDensityBenchmark {
    static final int INSTRUCTIONS = 200_000;

    private static final int RAM_SIZE = 48 * 1024 * 1024;
    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;
    private static final int WORKING_SET = 16 * 1024 * 1024;

    private static final long MMIO_BASE = 0x10000000L;

    @Param({"1", "4"})
    public int ramDevices;

    @Param({"0", "12"})
    public int mmioDevices;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;
    private long chainHead;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.paged(RAM_SIZE, "unsafe", ramDevices);
        cpu = vm.cpu();

        for (int i = 0; i < mmioDevices; i++) {
            vm.addDevice(MMIO_BASE + i * 0x100000L, new NullDevice());
        }

        codeStart = vm.usableStart();
        final long dataStart = Vm.align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);

        chainHead = vm.buildPointerChain(dataStart, WORKING_SET / Vm.PAGE_SIZE);

        vm.fill(codeStart, CODE_SIZE, R5Assembler.ld(1, 1, 0));

        vm.enterSupervisor(codeStart);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        vm.close();
    }

    @Benchmark
    public long chase() {
        vm.registers()[1] = chainHead;
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }
}
