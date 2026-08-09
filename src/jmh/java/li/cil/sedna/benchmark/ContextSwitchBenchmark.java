package li.cil.sedna.benchmark;

import li.cil.sedna.riscv.R5CPU;
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

    private static final int NODE_OFFSET = 64;

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
        final long dataStart = align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);

        chainHead = buildPointerChain(dataStart);

        // Machine mode trap handler: step the saved program counter past the ecall and return. This
        // is the smallest thing that behaves like a system call return.
        vm.write(handler,
            R5Assembler.csrrs(5, R5Assembler.CSR_MEPC, 0),
            R5Assembler.addi(5, 5, 4),
            R5Assembler.csrrw(0, R5Assembler.CSR_MEPC, 5),
            R5Assembler.MRET);
        vm.writeCSR(R5Assembler.CSR_MTVEC, handler);

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

    /** One node per page, linked in a strided order so consecutive loads land on different pages. */
    private long buildPointerChain(final long start) {
        final int stride = Math.max(1, WORKING_SET_PAGES / 3) | 1;

        long current = start + NODE_OFFSET;
        int index = 0;
        for (int i = 0; i < WORKING_SET_PAGES; i++) {
            final int next = (index + stride) % WORKING_SET_PAGES;
            final long nextAddress = start + (long) next * Vm.PAGE_SIZE + NODE_OFFSET;
            vm.store64(current, nextAddress);
            current = nextAddress;
            index = next;
        }

        return start + NODE_OFFSET;
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

    private static long align(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
