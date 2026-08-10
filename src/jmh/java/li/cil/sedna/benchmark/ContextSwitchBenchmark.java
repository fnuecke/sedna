package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CSR;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(ContextSwitchBenchmark.INSTRUCTIONS)
public class ContextSwitchBenchmark {
    static final int INSTRUCTIONS = 200_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;
    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;

    private static final int WORKING_SET_PAGES = 128;

    @Param({"syscall", "loads_only"})
    public String workload;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;
    private long chainHead;

    @Setup(Level.Trial)
    public void setUp() {
        vm = Vm.paged(RAM_SIZE);
        cpu = vm.cpu();

        final long handler = vm.usableStart();
        codeStart = handler + Vm.PAGE_SIZE;
        final long dataStart = Vm.align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);

        chainHead = vm.buildPointerChain(dataStart, WORKING_SET_PAGES);

        // Machine mode trap handler: step the saved program counter past the ecall and return. This
        // is the smallest thing that behaves like a system call return.
        vm.write(handler,
            R5Assembler.csrrs(5, R5CSR.MEPC, 0),
            R5Assembler.addi(5, 5, 4),
            R5Assembler.csrrw(0, R5CSR.MEPC, 5),
            R5Assembler.MRET);
        vm.writeCSR(R5CSR.MTVEC, handler);

        if ("syscall".equals(workload)) {
            // Alternate touching a page with a system call, so every load meets a TLB that the two
            // privilege transitions have just wiped.
            for (long address = codeStart; address < codeStart + CODE_SIZE; address += 8) {
                vm.write(address, R5Assembler.ld(1, 1, 0), R5Assembler.ECALL);
            }
        } else {
            vm.fill(codeStart, CODE_SIZE, R5Assembler.ld(1, 1, 0));
        }

        vm.enterSupervisor(codeStart);
    }


    @Benchmark
    public long run() {
        // Return to machine mode if we have to, then reset to start.
        vm.execute(R5Assembler.ECALL);
        vm.enterSupervisor(codeStart);

        vm.registers()[1] = chainHead;
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }

}
