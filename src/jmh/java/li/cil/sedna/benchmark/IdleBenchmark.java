package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IdleBenchmark {
    private static final int RAM_SIZE = 4 * 1024 * 1024;

    /** Matches what we use in OC2. */
    private static final int CYCLES_PER_STEP = 1_000;

    @Param({"idle", "running"})
    public String state;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        codeStart = vm.usableStart();

        if ("idle".equals(state)) {
            vm.write(codeStart, R5Assembler.WFI);
            vm.setProgramCounter(codeStart);
            cpu.step(1);
        } else {
            vm.fill(codeStart, (int) (vm.ramEnd() - codeStart), R5Assembler.NOP);
            vm.setProgramCounter(codeStart);
        }
    }

    @Benchmark
    public long step() {
        vm.setProgramCounter(codeStart);
        cpu.step(CYCLES_PER_STEP);
        return cpu.getTime();
    }
}
