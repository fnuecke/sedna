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

public final class InterruptPriorityTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final long TRAP_VECTOR = PHYSICAL_MEMORY_START + 0x800;
    private static final long STRAP_VECTOR = PHYSICAL_MEMORY_START + 0xC00;

    /** {@code jal x0, 0}, a one instruction infinite loop. */
    private static final int LOOP = jal(0, 0);

    private static final long INTERRUPT = R5.interrupt(R5.XLEN_64);

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);

        // Park the machine in a tight loop at both trap vectors so the cycles spent inside
        // step() after the trap of interest do not execute garbage.
        memoryMap.store(TRAP_VECTOR, LOOP, Sizes.SIZE_32_LOG2);
        memoryMap.store(STRAP_VECTOR, LOOP, Sizes.SIZE_32_LOG2);
        writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
        writeCSR(R5CSR.STVEC, STRAP_VECTOR);
    }

    @Test
    public void maskedDelegatedInterruptDoesNotShadowDeliverableOne() {
        // SSIP is delegated to S, STIP is not. Both pending and enabled in mie. In S-mode with
        // SIE=0 the delegated SSIP is masked, while the non-delegated STIP always fires (to M).
        writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        writeCSR(R5CSR.MIE, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(R5CSR.MIP, R5.SSIP_MASK | R5.STIP_MASK);

        writeCSR(R5CSR.MSTATUS, R5.STATUS_SPP_MASK); // SPP = S, SPIE = 0 -> SIE = 0 after SRET.
        execute(SRET);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, readCSR(R5CSR.MCAUSE), "the deliverable STIP must fire, not the masked SSIP");
        assertEquals(0, readCSR(R5CSR.SCAUSE), "the delegated, masked SSIP must not have been delivered");
    }

    @Test
    public void delegatedInterruptIsNotTakenInMachineMode() {
        // In M-mode with MIE=1, the delegated SSIP belongs to S and must not be taken; the
        // non-delegated STIP must be.
        writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        writeCSR(R5CSR.MIE, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(R5CSR.MIP, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(R5CSR.MSTATUS, R5.STATUS_MIE_MASK);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, readCSR(R5CSR.MCAUSE), "the non-delegated STIP must fire in M-mode");
    }

    @Test
    public void machineExternalInterruptCanBeEnabledAndFires() {
        writeCSR(R5CSR.MIE, R5.MEIP_MASK | R5.STIP_MASK);
        writeCSR(R5CSR.MIP, R5.STIP_MASK);
        cpu.raiseInterrupts((int) R5.MEIP_MASK); // MEIP is read-only in mip; raised by the PLIC.
        writeCSR(R5CSR.MSTATUS, R5.STATUS_MIE_MASK);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.MEIP_SHIFT, readCSR(R5CSR.MCAUSE), "MEIP must be deliverable and outrank STIP");
    }

    @Test
    public void delegatedInterruptFiresInSupervisorMode() {
        // Sanity check: with only the delegated SSIP pending and SIE=1, it must be delivered
        // to the S handler.
        writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        writeCSR(R5CSR.MIE, R5.SSIP_MASK);
        writeCSR(R5CSR.MIP, R5.SSIP_MASK);

        writeCSR(R5CSR.MSTATUS, R5.STATUS_SPP_MASK | R5.STATUS_SPIE_MASK); // SIE = 1 after SRET.
        execute(SRET);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.SSIP_SHIFT, readCSR(R5CSR.SCAUSE), "the delegated SSIP must be delivered to S");
    }

    private void writeCSR(final int csr, final long value) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = value;
        execute(csrrw(0, csr, 1));
    }

    private long readCSR(final int csr) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[2] = 0;
        execute(csrrs(2, csr, 0));
        return registers[2];
    }

    private void execute(final int instruction) {
        try {
            memoryMap.store(PHYSICAL_MEMORY_START, instruction, Sizes.SIZE_32_LOG2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }

        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);
        cpu.getDebugInterface().step();
    }
}
