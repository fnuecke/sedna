package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TrapStatusTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final int CSR_MSTATUS = 0x300;
    private static final int CSR_SSTATUS = 0x100;

    private static final long TRAP_VECTOR = PHYSICAL_MEMORY_START + 0x800;

    private static final int MRET = 0x30200073;
    private static final int SRET = 0x10200073;
    private static final int ECALL = 0x00000073;

    // WPRI bits adjacent to the IE/PIE bits, which the broken restore polluted.
    private static final long WPRI_BITS = (1L << 2) | (1L << 6);
    // UIE/UPIE, hardwired zero without the N extension.
    private static final long USER_IE_BITS = R5.STATUS_UIE_MASK | R5.STATUS_UPIE_MASK;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = TRAP_VECTOR;
        execute(csrrw(0, 0x305 /* mtvec */, 1));
    }

    @Test
    public void mretRestoresMIEFromMPIE() {
        writeMSTATUS(R5.STATUS_MPIE_MASK | ((long) R5.PRIVILEGE_M << R5.STATUS_MPP_SHIFT));
        execute(MRET);

        final long mstatus = readMSTATUS();
        assertEquals(R5.STATUS_MIE_MASK, mstatus & R5.STATUS_MIE_MASK, "MIE must be restored from MPIE");
        assertEquals(R5.STATUS_MPIE_MASK, mstatus & R5.STATUS_MPIE_MASK, "MPIE must be set after MRET");
        assertEquals(0, mstatus & R5.STATUS_MPP_MASK, "MPP must be set to U after MRET");
        assertEquals(0, mstatus & WPRI_BITS, "WPRI bits must remain zero");
    }

    @Test
    public void trapFromUModeSavesMachineIEIntoMPIE() {
        // Drop to U-mode with MIE set; the ecall's trap entry must save MIE (the target mode's
        // IE bit), not U-mode's nonexistent UIE.
        writeMSTATUS(R5.STATUS_MPIE_MASK); // MPP = U.
        execute(MRET);
        execute(ECALL);

        final long mstatus = readMSTATUS();
        assertEquals(R5.STATUS_MPIE_MASK, mstatus & R5.STATUS_MPIE_MASK, "MPIE must hold the pre-trap MIE");
        assertEquals(0, mstatus & R5.STATUS_MIE_MASK, "MIE must be cleared on trap entry");
        assertEquals(0, mstatus & R5.STATUS_MPP_MASK, "MPP must hold the interrupted privilege (U)");

        // And returning must bring MIE back: after MRET we are in U-mode again (mstatus is not
        // readable there), so trap back in and check the IE bit the entry saved.
        execute(MRET);
        execute(ECALL);
        assertEquals(R5.STATUS_MPIE_MASK, readMSTATUS() & R5.STATUS_MPIE_MASK,
            "MRET must restore MIE, observed via the MPIE the following trap saves");
    }

    @Test
    public void sretRestoresSIEFromSPIE() {
        writeMSTATUS(R5.STATUS_SPIE_MASK | R5.STATUS_SPP_MASK); // SPP = S.
        execute(SRET);

        // Now in S-mode; sstatus is the window we have.
        final long sstatus = readSSTATUS();
        assertEquals(R5.STATUS_SIE_MASK, sstatus & R5.STATUS_SIE_MASK, "SIE must be restored from SPIE");
        assertEquals(R5.STATUS_SPIE_MASK, sstatus & R5.STATUS_SPIE_MASK, "SPIE must be set after SRET");
        assertEquals(0, sstatus & R5.STATUS_SPP_MASK, "SPP must be set to U after SRET");
        assertEquals(0, sstatus & (WPRI_BITS | USER_IE_BITS), "WPRI and user IE bits must remain zero");
    }

    @Test
    public void sretToUModeRestoresSIENotUIE() {
        writeMSTATUS(R5.STATUS_SPIE_MASK); // SPP = U.
        execute(SRET);

        // Now in U-mode; trap back to M to inspect state.
        execute(ECALL);
        final long mstatus = readMSTATUS();
        assertEquals(R5.STATUS_SIE_MASK, mstatus & R5.STATUS_SIE_MASK, "SIE must be restored even when returning to U");
        assertEquals(0, mstatus & (WPRI_BITS | USER_IE_BITS), "WPRI and user IE bits must remain zero");
    }

    @Test
    public void sstatusHardwiresUserIEBitsToZero() {
        writeMSTATUS(USER_IE_BITS | R5.STATUS_SIE_MASK);
        assertEquals(0, readSSTATUS() & USER_IE_BITS, "sstatus must hide UIE/UPIE without the N extension");
    }

    private void writeMSTATUS(final long value) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = value;
        execute(csrrw(0, CSR_MSTATUS, 1));
    }

    private long readMSTATUS() {
        return readCSR(CSR_MSTATUS);
    }

    private long readSSTATUS() {
        return readCSR(CSR_SSTATUS);
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
