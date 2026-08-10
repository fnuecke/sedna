package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class StepTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;

    /**
     * Traces are bounded by the page they start in, so a page of straight-line code is the longest
     * a single trace can get, and therefore the largest overshoot a single step can produce.
     */
    private static final int MAX_INSTRUCTIONS_PER_TRACE = (1 << R5.PAGE_ADDRESS_SHIFT) / 4;

    private static final int CYCLES_PER_STEP = 100;
    private static final int STEP_COUNT = 100;
    private static final int PHYSICAL_MEMORY_LENGTH = 1024 * 1024;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        for (int offset = 0; offset < PHYSICAL_MEMORY_LENGTH; offset += 4) {
            memoryMap.store(PHYSICAL_MEMORY_START + offset, NOP, Sizes.SIZE_32_LOG2);
        }

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);
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
    public void tightGuestLoopRespectsTheCycleBudget() throws MemoryAccessException {
        // An infinite two-instruction loop.
        final long loop = PHYSICAL_MEMORY_START + 0x1000;
        memoryMap.store(loop, addi(5, 5, 1), Sizes.SIZE_32_LOG2);
        memoryMap.store(loop + 4, bne(5, 0, -4), Sizes.SIZE_32_LOG2);
        cpu.reset(true, loop);
        cpu.setXLEN(R5.XLEN_64);

        final long start = cpu.getTime();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
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
