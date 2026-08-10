package li.cil.sedna.riscv;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class StepTests {
    /**
     * Traces are bounded by the page they start in, so a page of straight-line code is the longest
     * a single trace can get, and therefore the largest overshoot a single step can produce.
     */
    private static final int MAX_INSTRUCTIONS_PER_TRACE = (1 << R5.PAGE_ADDRESS_SHIFT) / 4;

    private static final int CYCLES_PER_STEP = 100;
    private static final int STEP_COUNT = 100;
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
    public void overshootIsRepaidAcrossSteps() {
        final long start = cpu.getTime();

        for (int i = 0; i < STEP_COUNT; i++) {
            cpu.step(CYCLES_PER_STEP);
        }

        final long executed = cpu.getTime() - start;
        final long requested = (long) CYCLES_PER_STEP * STEP_COUNT;
        final long overrun = executed - requested;

        assertTrue(overrun <= MAX_INSTRUCTIONS_PER_TRACE,
            String.format("cumulative overrun must stay within one trace, but ran %d cycles for %d requested (overrun %d, one trace is %d)",
                executed, requested, overrun, MAX_INSTRUCTIONS_PER_TRACE));
    }

    @Test
    public void repayingDebtDoesNotStarveTheCpu() {
        final long start = cpu.getTime();

        for (int i = 0; i < STEP_COUNT; i++) {
            cpu.step(CYCLES_PER_STEP);
        }

        final long executed = cpu.getTime() - start;
        final long requested = (long) CYCLES_PER_STEP * STEP_COUNT;

        assertTrue(executed >= requested,
            String.format("must not run fewer cycles than requested, but ran %d for %d requested", executed, requested));
    }

    @Test
    public void tightGuestLoopRespectsTheCycleBudget() {
        // An infinite two-instruction loop.
        final long loop = Vm.RAM_START + 0x1000;
        vm.write(loop, addi(5, 5, 1), bne(5, 0, -4));
        cpu.reset(true, loop);
        cpu.setXLEN(R5.XLEN_64);

        final long start = cpu.getTime();
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> cpu.step(CYCLES_PER_STEP),
            "the in-trace cycle budget check must bound guest loops");
        final long executed = cpu.getTime() - start;

        assertTrue(executed >= CYCLES_PER_STEP,
            String.format("must not run fewer cycles than requested, but ran %d for %d requested", executed, CYCLES_PER_STEP));
        assertTrue(executed <= CYCLES_PER_STEP + MAX_INSTRUCTIONS_PER_TRACE,
            String.format("the in-trace budget check must bound the loop, but ran %d cycles for %d requested", executed, CYCLES_PER_STEP));
    }

    @Test
    public void budgetLargerThanATraceIsSpentInOneCall() {
        final int cycles = MAX_INSTRUCTIONS_PER_TRACE * 4;

        final long start = cpu.getTime();
        cpu.step(cycles);
        final long executed = cpu.getTime() - start;

        assertTrue(executed >= cycles,
            String.format("expected at least %d cycles to be executed, but got %d", cycles, executed));
        assertTrue(executed <= cycles + MAX_INSTRUCTIONS_PER_TRACE,
            String.format("expected at most %d cycles to be executed, but got %d", cycles + MAX_INSTRUCTIONS_PER_TRACE, executed));
    }
}
