package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class InterruptPriorityTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final long TRAP_VECTOR = PHYSICAL_MEMORY_START + 0x800;
    private static final long STRAP_VECTOR = PHYSICAL_MEMORY_START + 0xC00;

    private static final int CSR_MSTATUS = 0x300;
    private static final int CSR_MIDELEG = 0x303;
    private static final int CSR_MIE = 0x304;
    private static final int CSR_MTVEC = 0x305;
    private static final int CSR_STVEC = 0x105;
    private static final int CSR_SCAUSE = 0x142;
    private static final int CSR_MCAUSE = 0x342;

    private static final int SRET = 0x10200073;
    private static final int LOOP = 0x0000006F; // jal x0, 0

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
        writeCSR(CSR_MTVEC, TRAP_VECTOR);
        writeCSR(CSR_STVEC, STRAP_VECTOR);
    }

    @Test
    public void maskedDelegatedInterruptDoesNotShadowDeliverableOne() {
        // SSIP is delegated to S, STIP is not. Both pending and enabled in mie. In S-mode with
        // SIE=0 the delegated SSIP is masked, while the non-delegated STIP always fires (to M).
        writeCSR(CSR_MIDELEG, R5.SSIP_MASK);
        writeCSR(CSR_MIE, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(0x344 /* mip */, R5.SSIP_MASK | R5.STIP_MASK);

        writeCSR(CSR_MSTATUS, R5.STATUS_SPP_MASK); // SPP = S, SPIE = 0 -> SIE = 0 after SRET.
        execute(SRET);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, readCSR(CSR_MCAUSE), "the deliverable STIP must fire, not the masked SSIP");
        assertEquals(0, readCSR(CSR_SCAUSE), "the delegated, masked SSIP must not have been delivered");
    }

    @Test
    public void delegatedInterruptIsNotTakenInMachineMode() {
        // In M-mode with MIE=1, the delegated SSIP belongs to S and must not be taken; the
        // non-delegated STIP must be.
        writeCSR(CSR_MIDELEG, R5.SSIP_MASK);
        writeCSR(CSR_MIE, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(0x344 /* mip */, R5.SSIP_MASK | R5.STIP_MASK);
        writeCSR(CSR_MSTATUS, R5.STATUS_MIE_MASK);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, readCSR(CSR_MCAUSE), "the non-delegated STIP must fire in M-mode");
    }

    @Test
    public void machineExternalInterruptCanBeEnabledAndFires() {
        writeCSR(CSR_MIE, R5.MEIP_MASK | R5.STIP_MASK);
        writeCSR(0x344 /* mip */, R5.STIP_MASK);
        cpu.raiseInterrupts((int) R5.MEIP_MASK); // MEIP is read-only in mip; raised by the PLIC.
        writeCSR(CSR_MSTATUS, R5.STATUS_MIE_MASK);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.MEIP_SHIFT, readCSR(CSR_MCAUSE), "MEIP must be deliverable and outrank STIP");
    }

    @Test
    public void delegatedInterruptFiresInSupervisorMode() {
        // Sanity check: with only the delegated SSIP pending and SIE=1, it must be delivered
        // to the S handler.
        writeCSR(CSR_MIDELEG, R5.SSIP_MASK);
        writeCSR(CSR_MIE, R5.SSIP_MASK);
        writeCSR(0x344 /* mip */, R5.SSIP_MASK);

        writeCSR(CSR_MSTATUS, R5.STATUS_SPP_MASK | R5.STATUS_SPIE_MASK); // SIE = 1 after SRET.
        execute(SRET);
        cpu.step(1);

        assertEquals(INTERRUPT | R5.SSIP_SHIFT, readCSR(CSR_SCAUSE), "the delegated SSIP must be delivered to S");
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

    private static int csrrw(final int rd, final int csr, final int rs1) {
        return csr(rd, csr, rs1, 0b001);
    }

    private static int csrrs(final int rd, final int csr, final int rs1) {
        return csr(rd, csr, rs1, 0b010);
    }

    private static int csr(final int rd, final int csr, final int rs1, final int funct3) {
        return (csr << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0b1110011;
    }
}
