package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(BackendBenchmark.INSTRUCTIONS)
public class BackendBenchmark {
    static final int INSTRUCTIONS = 1_000_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;
    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;

    @Param({"unsafe", "bytebuffer", "mapped"})
    public String memory;

    @Param({"nop", "ld", "sd"})
    public String instruction;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE, memory);
        cpu = vm.cpu();

        codeStart = vm.usableStart();
        final long dataAddress = codeStart + CODE_SIZE;

        vm.registers()[1] = dataAddress;
        vm.store64(dataAddress, 0x0123456789ABCDEFL);

        vm.fill(codeStart, CODE_SIZE, switch (instruction) {
            case "nop" -> R5Assembler.NOP;
            case "ld" -> R5Assembler.ld(2, 1, 0);
            case "sd" -> R5Assembler.sd(2, 1, 0);
            default -> throw new IllegalArgumentException(instruction);
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        vm.close();
    }

    @Benchmark
    public long execute() {
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }
}
