package li.cil.sedna.riscv;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BreakpointTests {
    private static final int MAX_INSTRUCTIONS_PER_TRACE = (1 << R5.PAGE_ADDRESS_SHIFT) / 4;

    private static final int CYCLES_PER_STEP = 100;
    private static final int RAM_SIZE = 1024 * 1024;

    private Vm vm;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.fill(Vm.RAM_START, RAM_SIZE, NOP);
        cpu = vm.cpu();
    }

    @Test
    public void breakpointHitEndsTheStep() {
        final long breakpoint = Vm.RAM_START + 8;
        cpu.getDebugInterface().addBreakpoint(breakpoint);

        final long start = cpu.getTime();
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> cpu.step(CYCLES_PER_STEP),
                "a breakpoint hit must end the step instead of re-entering at the same pc");

        Assertions.assertEquals(breakpoint, vm.programCounter(), "must stop on the breakpoint");
        Assertions.assertEquals(2, cpu.getTime() - start,
                "must stop before executing the instruction the breakpoint sits on");
    }

    @Test
    public void breakpointDoesNotInflateTheNextStepsBudget() {
        final int largeBudget = 10 * CYCLES_PER_STEP * MAX_INSTRUCTIONS_PER_TRACE;
        cpu.getDebugInterface().addBreakpoint(Vm.RAM_START + 8);

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> cpu.step(largeBudget),
                "a breakpoint hit must end the step instead of re-entering at the same pc");
        vm.stepOnce(); // Step over the breakpoint, the way a debugger resumes.

        final long start = cpu.getTime();
        cpu.step(CYCLES_PER_STEP);
        final long executed = cpu.getTime() - start;

        assertTrue(executed >= CYCLES_PER_STEP,
                String.format("must not run fewer cycles than requested, but ran %d for %d requested", executed, CYCLES_PER_STEP));
        assertTrue(executed <= CYCLES_PER_STEP + MAX_INSTRUCTIONS_PER_TRACE,
                String.format("the budget unspent when the breakpoint hit must be forfeited, not repaid, but ran %d cycles for %d requested",
                        executed, CYCLES_PER_STEP));
    }

    @Test
    public void breakpointBeforeTheTraceEntryInTheSamePageIsHit() {
        final long entry = Vm.RAM_START + 0x800;
        final long breakpoint = Vm.RAM_START + 0x100;
        vm.write(entry, jal(0, (int) (breakpoint - entry)));

        final AtomicLong hits = new AtomicLong();
        cpu.getDebugInterface().addBreakpointListener(address -> hits.incrementAndGet());
        cpu.getDebugInterface().addBreakpoint(breakpoint);
        vm.setProgramCounter(entry);

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cpu.step(CYCLES_PER_STEP));

        Assertions.assertEquals(1, hits.get(),
                "the fetch TLB's breakpoint set must cover the whole page, not the page-sized window starting at the fetch address");
        Assertions.assertEquals(breakpoint, vm.programCounter(), "must stop on the breakpoint");
    }
}
