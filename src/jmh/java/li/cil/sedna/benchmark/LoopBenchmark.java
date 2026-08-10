package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(LoopBenchmark.INSTRUCTIONS)
public class LoopBenchmark {
    static final int INSTRUCTIONS = 240_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;

    @Param({"2", "4", "8", "16", "64", "256"})
    public int bodySize;

    private Vm vm;
    private R5CPU cpu;
    private long loopStart;
    private long iterations;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        loopStart = vm.usableStart();
        iterations = INSTRUCTIONS / bodySize;

        // x5 is the loop counter; x6 does filler work so the body is bodySize instructions total.
        long address = loopStart;
        vm.write(address, R5Assembler.addi(5, 5, -1));
        address += 4;
        for (int i = 0; i < bodySize - 2; i++) {
            vm.write(address, R5Assembler.addi(6, 6, 1));
            address += 4;
        }
        vm.write(address, R5Assembler.bne(5, 0, (int) (loopStart - address)));
        address += 4;

        vm.write(address, R5Assembler.jal(0, 0));
    }

    @Benchmark
    public long run() {
        vm.registers()[5] = iterations;
        vm.registers()[6] = 0;
        vm.setProgramCounter(loopStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }
}
