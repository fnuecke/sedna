package li.cil.sedna.z80;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public final class Z80BreakpointTests {
    private static final int CYCLES_PER_STEP = 1000;

    private static final int REG_AF = 0;

    private PhysicalMemory memory;
    private Z80CPU cpu;

    @BeforeEach
    public void setupEach() {
        final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        memory = Memory.create(0x10000);
        assertTrue(memoryMap.addDevice(0, memory));
        cpu = Z80CPU.create(memoryMap, new SimpleMemoryMap());
        cpu.reset(true, 0);
    }

    @Test
    public void breakpointHitEndsTheStep() throws MemoryAccessException {
        write(0x00, 0x00, 0x00, 0x3C); // NOP NOP NOP INC A

        final AtomicLong hits = new AtomicLong();
        cpu.getDebugInterface().addBreakpointListener(address -> hits.incrementAndGet());
        cpu.getDebugInterface().addBreakpoint(3);

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> cpu.step(CYCLES_PER_STEP),
                "a breakpoint hit must end the step instead of re-entering at the same pc");

        assertEquals(1, hits.get());
        assertEquals(3, cpu.getDebugInterface().getProgramCounter(), "must stop on the breakpoint");
        assertEquals(12, cpu.getCycles(), "must stop before executing the instruction the breakpoint sits on");
    }

    @Test
    public void steppingOverABreakpointResumes() throws MemoryAccessException {
        write(0x00, 0x00, 0x00, 0x3C, 0x76); // NOP NOP NOP INC A HALT

        cpu.getDebugInterface().addBreakpoint(3);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cpu.step(CYCLES_PER_STEP));

        cpu.getDebugInterface().step(); // Step over the breakpoint, the way a debugger resumes.
        assertEquals(4, cpu.getDebugInterface().getProgramCounter());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cpu.step(CYCLES_PER_STEP));
        assertTrue(cpu.isHalted(), "the resumed step must reach the HALT");
    }

    @Test
    public void breakpointDoesNotInflateTheNextStepsBudget() throws MemoryAccessException {
        write(0x00, 0x00, 0x00, 0x3C, 0xC3, 0x03, 0x00); // NOP NOP NOP 0x0003: INC A; JP 0x0003

        cpu.getDebugInterface().addBreakpoint(3);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cpu.step(100 * CYCLES_PER_STEP));
        cpu.getDebugInterface().removeBreakpoint(3);

        final long start = cpu.getCycles();
        cpu.step(CYCLES_PER_STEP);
        final long executed = cpu.getCycles() - start;

        assertTrue(executed >= CYCLES_PER_STEP,
                String.format("must not run fewer cycles than requested, but ran %d for %d requested", executed, CYCLES_PER_STEP));
        assertTrue(executed < 2 * CYCLES_PER_STEP,
                String.format("the budget unspent when the breakpoint hit must be forfeited, not repaid, but ran %d cycles for %d requested",
                        executed, CYCLES_PER_STEP));
    }

    @Test
    public void anEmptyBreakpointSetDoesNotStopExecution() throws MemoryAccessException {
        write(0x00, 0x00, 0x00, 0x76); // NOP NOP NOP HALT

        cpu.getDebugInterface().addBreakpoint(3);
        cpu.getDebugInterface().removeBreakpoint(3);

        cpu.step(CYCLES_PER_STEP);
        assertTrue(cpu.isHalted());
    }

    @Test
    public void aBreakpointInTheEiShadowKeepsTheInterruptOut() throws MemoryAccessException {
        write(0xED, 0x56, // IM 1
                0xFB,     // EI
                0x3C,     // INC A
                0x76);    // HALT
        memory.store(0x0038, 0xC9, Sizes.SIZE_8_LOG2); // RET, at the IM 1 vector.

        cpu.getDebugInterface().addBreakpoint(3);
        cpu.raiseInterrupt(0);

        cpu.step(CYCLES_PER_STEP);
        assertEquals(3, cpu.getDebugInterface().getProgramCounter(), "must stop on the breakpoint");

        cpu.getDebugInterface().step();

        // A is 0xFF after a hard reset, so INC A wraps it to zero.
        assertEquals(0x00, cpu.getDebugInterface().getRegister(REG_AF) >>> 8,
                "the instruction after EI must run before an interrupt is accepted, even with a breakpoint on it");
        assertEquals(4, cpu.getDebugInterface().getProgramCounter());
    }

    private void write(final int... bytes) throws MemoryAccessException {
        for (int i = 0; i < bytes.length; i++) {
            memory.store(i, bytes[i], Sizes.SIZE_8_LOG2);
        }
    }
}
