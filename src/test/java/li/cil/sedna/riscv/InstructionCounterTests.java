package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class InstructionCounterTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 64 * 1024;

    private static final int IDLE_CYCLES = 10 * 10_000;

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
    public void executingInstructionsAdvancesBothCounters() {
        cpu.step(1000);

        assertTrue(cpu.getInstructionsRetired() > 0, "instructions must be counted");
        assertTrue(cpu.getTime() > 0, "cycles must be counted");
    }

    @Test
    public void idlingDoesNotRetireInstructions() throws MemoryAccessException {
        memoryMap.store(PHYSICAL_MEMORY_START, WFI, Sizes.SIZE_32_LOG2);

        cpu.step(1);

        final long retiredWhenParked = cpu.getInstructionsRetired();
        final long cyclesWhenParked = cpu.getTime();

        for (int i = 0; i < 100; i++) {
            cpu.step(10_000);
        }

        assertEquals(retiredWhenParked, cpu.getInstructionsRetired(),
            "an idle hart must not appear to retire instructions");
        assertTrue(cpu.getTime() > cyclesWhenParked,
            "cycles must keep advancing while idle, since they are the guest's time source");
    }

    @Test
    public void wakingResumesRetiringInstructions() throws MemoryAccessException {
        // Point the trap vector at the field of NOPs, so a taken interrupt lands on valid code.
        writeCSR(R5CSR.MTVEC, PHYSICAL_MEMORY_START + 0x2000);
        // WFI only parks the hart while no enabled interrupt is pending, and raising one only wakes
        // it if that interrupt is enabled, so mie has to be set up before the WFI executes.
        writeCSR(R5CSR.MIE, R5.MSIP_MASK);

        memoryMap.store(PHYSICAL_MEMORY_START, WFI, Sizes.SIZE_32_LOG2);
        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);

        cpu.step(1);
        cpu.step(10_000);
        final long retiredWhileParked = cpu.getInstructionsRetired();

        cpu.raiseInterrupts(R5.MSIP_MASK);
        cpu.step(10_000);

        assertTrue(cpu.getInstructionsRetired() > retiredWhileParked,
            "a woken hart must retire instructions again");
    }

    @Test
    public void straightLineCodeRetiresOneInstructionEach() {
        final long before = cpu.getInstructionsRetired();

        cpu.step(1000);

        final long executed = cpu.getInstructionsRetired() - before;
        final long cycles = cpu.getTime();

        assertEquals(cycles, executed, "with no traps and no idling, cycles and instructions agree");
    }

    @Test
    public void hardResetClearsBothCounters() {
        cpu.step(1000);
        assertTrue(cpu.getInstructionsRetired() > 0);

        cpu.reset(true, PHYSICAL_MEMORY_START);

        assertEquals(0, cpu.getInstructionsRetired());
        assertEquals(0, cpu.getTime());
    }

    @Test
    public void csrsReportTheTwoCountersSeparately() throws MemoryAccessException {
        memoryMap.store(PHYSICAL_MEMORY_START, WFI, Sizes.SIZE_32_LOG2);

        cpu.step(1);
        for (int i = 0; i < 10; i++) {
            cpu.step(IDLE_CYCLES / 10);
        }

        final long cycle = readCSR(R5CSR.MCYCLE);
        final long instret = readCSR(R5CSR.MINSTRET);

        assertTrue(cycle - instret > IDLE_CYCLES / 2,
            String.format("after idling for ~%d cycles, cycles (%d) must have run far ahead of instructions retired (%d)",
                IDLE_CYCLES, cycle, instret));
    }

    private long readCSR(final int csr) throws MemoryAccessException {
        final long address = PHYSICAL_MEMORY_START + 0x1000;
        memoryMap.store(address, csrrs(1, csr, 0), Sizes.SIZE_32_LOG2);

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = 0;
        cpu.getDebugInterface().setProgramCounter(address);
        cpu.getDebugInterface().step();

        return registers[1];
    }

    private void writeCSR(final int csr, final long value) throws MemoryAccessException {
        final long address = PHYSICAL_MEMORY_START + 0x1000;
        memoryMap.store(address, csrrw(0, csr, 1), Sizes.SIZE_32_LOG2);

        cpu.getDebugInterface().getGeneralRegisters()[1] = value;
        cpu.getDebugInterface().setProgramCounter(address);
        cpu.getDebugInterface().step();
    }
}
