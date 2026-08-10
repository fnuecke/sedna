package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CSR;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(TrapBenchmark.TRAPS)
public class TrapBenchmark {
    static final int TRAPS = 10_000;

    private static final int RAM_SIZE = 8 * 1024 * 1024;

    private static final long UNMAPPED_ADDRESS = 0x1000L;

    @Param({"illegal_instruction", "load_fault", "ecall"})
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
        vm.writeCSR(R5CSR.MTVEC, codeStart);
        vm.fill(codeStart, Vm.PAGE_SIZE, trapInstruction());
        vm.setProgramCounter(codeStart);

        vm.registers()[1] = UNMAPPED_ADDRESS;
    }

    private int trapInstruction() {
        return switch (kind) {
            case "illegal_instruction" -> R5Assembler.ILLEGAL;
            case "load_fault" -> R5Assembler.ld(2, 1, 0);
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
