package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5;
import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CSR;
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

    @Param({"nop", "add", "mul", "mulh", "mulhsu", "mulhu", "ld", "sd",
        "fadd_s", "fadd_d", "fmul_d", "fdiv_d", "fsqrt_d", "fmadd_d",
        "amoadd_w", "amoadd_d", "amoswap_d", "lr_sc_w", "lr_sc_d",
        "c_addi", "c_mv"})
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

        // x1 addresses the data word loads, stores and atomics operate on. Keeping every access on
        // one page means this measures the instruction, not the memory subsystem.
        vm.registers()[1] = dataAddress;
        vm.store64(dataAddress, 0x0123456789ABCDEFL);

        // Floating point instructions trap while mstatus.FS is Off, so turn the FPU on.
        vm.setCSRBits(R5CSR.MSTATUS, (long) R5.FS_INITIAL << R5.STATUS_FS_SHIFT);

        final long[] registers = vm.registers();
        registers[3] = 0x0123456789ABCDEFL;
        registers[4] = 0x7EDCBA9876543210L;

        registers[5] = Double.doubleToRawLongBits(1.5);
        vm.execute(R5Assembler.fmvDX(3, 5));
        registers[5] = Double.doubleToRawLongBits(2.25);
        vm.execute(R5Assembler.fmvDX(4, 5));
        registers[5] = Double.doubleToRawLongBits(0.75);
        vm.execute(R5Assembler.fmvDX(5, 5));
        registers[6] = Float.floatToRawIntBits(1.5f) & 0xFFFFFFFFL;
        vm.execute(R5Assembler.fmvWX(6, 6));
        registers[7] = Float.floatToRawIntBits(2.25f) & 0xFFFFFFFFL;
        vm.execute(R5Assembler.fmvWX(7, 7));

        // c_mv copies x5 to x6; x5 still holds the last staging value, which is non-zero.

        if (isCompressed()) {
            vm.fillCompressed(codeStart, CODE_SIZE, encode()[0]);
        } else {
            vm.fillPattern(codeStart, CODE_SIZE, encode());
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        vm.close();
    }

    private boolean isCompressed() {
        return instruction.startsWith("c_");
    }

    private int[] encode() {
        return switch (instruction) {
            case "nop" -> new int[]{R5Assembler.NOP};
            case "add" -> new int[]{R5Assembler.add(2, 3, 4)};
            case "mul" -> new int[]{R5Assembler.mul(2, 3, 4)};
            case "mulh" -> new int[]{R5Assembler.mulh(2, 3, 4)};
            case "mulhsu" -> new int[]{R5Assembler.mulhsu(2, 3, 4)};
            case "mulhu" -> new int[]{R5Assembler.mulhu(2, 3, 4)};
            case "ld" -> new int[]{R5Assembler.ld(2, 1, 0)};
            case "sd" -> new int[]{R5Assembler.sd(2, 1, 0)};
            case "fadd_s" -> new int[]{R5Assembler.faddS(2, 6, 7)};
            case "fadd_d" -> new int[]{R5Assembler.faddD(2, 3, 4)};
            case "fmul_d" -> new int[]{R5Assembler.fmulD(2, 3, 4, R5.FCSR_FRM_DYN)};
            case "fdiv_d" -> new int[]{R5Assembler.fdivD(2, 3, 4, R5.FCSR_FRM_DYN)};
            case "fsqrt_d" -> new int[]{R5Assembler.fsqrtD(2, 3, R5.FCSR_FRM_DYN)};
            case "fmadd_d" -> new int[]{R5Assembler.fmaddD(2, 3, 4, 5, R5.FCSR_FRM_DYN)};
            case "amoadd_w" -> new int[]{R5Assembler.amoaddW(2, 1, 3)};
            case "amoadd_d" -> new int[]{R5Assembler.amoaddD(2, 1, 3)};
            case "amoswap_d" -> new int[]{R5Assembler.amoswapD(2, 1, 3)};
            case "lr_sc_w" -> new int[]{R5Assembler.lrW(2, 1), R5Assembler.scW(2, 1, 4)};
            case "lr_sc_d" -> new int[]{R5Assembler.lrD(2, 1), R5Assembler.scD(2, 1, 4)};
            case "c_addi" -> new int[]{R5Assembler.cAddi(6, 1)};
            case "c_mv" -> new int[]{R5Assembler.cMv(6, 5)};
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
