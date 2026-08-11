package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(CallBenchmark.INSTRUCTIONS)
public class CallBenchmark {
    static final int INSTRUCTIONS = 240_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;

    @Param({"same_page", "cross_page"})
    public String distance;

    @Param({"4", "16"})
    public int calleeSize;

    private Vm vm;
    private R5CPU cpu;
    private long loopStart;
    private long iterations;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        loopStart = vm.usableStart();
        final long callee = "same_page".equals(distance)
            ? loopStart + 128
            : loopStart + Vm.PAGE_SIZE;

        // Loop: decrement x5, call the callee, loop while x5 != 0, then spin.
        long address = loopStart;
        vm.write(address, R5Assembler.addi(5, 5, -1));
        address += 4;
        vm.write(address, R5Assembler.jal(1, (int) (callee - address)));
        address += 4;
        vm.write(address, R5Assembler.bne(5, 0, (int) (loopStart - address)));
        address += 4;
        vm.write(address, R5Assembler.jal(0, 0));

        // Callee: filler work, then return.
        address = callee;
        for (int i = 0; i < calleeSize - 1; i++) {
            vm.write(address, R5Assembler.addi(6, 6, 1));
            address += 4;
        }
        vm.write(address, R5Assembler.jalr(0, 1, 0));

        final int perIteration = 3 + calleeSize;
        iterations = INSTRUCTIONS / perIteration;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        vm.close();
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
