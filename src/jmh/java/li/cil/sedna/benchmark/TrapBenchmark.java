package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(TrapBenchmark.TRAPS)
public class TrapBenchmark {
    static final int TRAPS = 10_000;

    private static final int RAM_SIZE = 8 * 1024 * 1024;

    @Param({"illegal_instruction", "ecall"})
    public String kind;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        codeStart = vm.usableStart();

        // Trap straight back to the faulting instruction: the handler is the instruction itself, so
        // the machine does nothing but take traps.
        vm.writeCSR(R5Assembler.CSR_MTVEC, codeStart);
        vm.fill(codeStart, Vm.PAGE_SIZE, trapInstruction());
        vm.setProgramCounter(codeStart);
    }

    private int trapInstruction() {
        return switch (kind) {
            case "illegal_instruction" -> R5Assembler.ILLEGAL;
            case "ecall" -> R5Assembler.ECALL;
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @Benchmark
    public long trap() {
        vm.setProgramCounter(codeStart);
        cpu.step(TRAPS);
        return cpu.getTime();
    }
}
