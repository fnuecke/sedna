package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5;
import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(DispatchBenchmark.INSTRUCTIONS)
public class DispatchBenchmark {
    static final int INSTRUCTIONS = 1_000_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;

    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;

    @Param({"nop", "add", "mul", "mulh", "mulhsu", "mulhu", "ld", "sd", "fadd_d"})
    public String instruction;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        codeStart = vm.usableStart();
        final long dataAddress = codeStart + CODE_SIZE;

        // x1 addresses the data word loads and stores operate on. Keeping every access on one page
        // means this measures the instruction, not the memory subsystem.
        vm.registers()[1] = dataAddress;
        vm.store64(dataAddress, 0x0123456789ABCDEFL);

        // Floating point instructions trap while mstatus.FS is Off, so turn the FPU on.
        vm.setCSRBits(R5.CSR_MSTATUS, (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT);

        final long[] registers = vm.registers();
        registers[3] = 0x0123456789ABCDEFL;
        registers[4] = 0x7EDCBA9876543210L;

        registers[5] = Double.doubleToRawLongBits(1.5);
        vm.execute(R5Assembler.fmvDX(3, 5));
        registers[5] = Double.doubleToRawLongBits(2.25);
        vm.execute(R5Assembler.fmvDX(4, 5));

        vm.fill(codeStart, CODE_SIZE, encode());
    }

    private int encode() {
        return switch (instruction) {
            case "nop" -> R5Assembler.NOP;
            case "add" -> R5Assembler.add(2, 3, 4);
            case "mul" -> R5Assembler.mul(2, 3, 4);
            case "mulh" -> R5Assembler.mulh(2, 3, 4);
            case "mulhsu" -> R5Assembler.mulhsu(2, 3, 4);
            case "mulhu" -> R5Assembler.mulhu(2, 3, 4);
            case "ld" -> R5Assembler.ld(2, 1, 0);
            case "sd" -> R5Assembler.sd(2, 1, 0);
            case "fadd_d" -> R5Assembler.faddD(2, 3, 4);
            default -> throw new IllegalArgumentException(instruction);
        };
    }

    @Benchmark
    public long execute() {
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }
}
