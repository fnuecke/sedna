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

public final class SATPTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 4 * 1024;

    private static final long PPN = 0x80000L;

    private MemoryMap memoryMap;
    private R5CPU cpu;

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));
        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, PHYSICAL_MEMORY_START);
        cpu.setXLEN(R5.XLEN_64);
    }

    @Test
    public void writeSv39IsAccepted() throws MemoryAccessException {
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
    }

    @Test
    public void writeSv48IsAccepted() throws MemoryAccessException {
        assertEquals(R5.SATP_MODE_SV48 | PPN, writeThenReadSatp(R5.SATP_MODE_SV48 | PPN));
    }

    @Test
    public void writeBareFromSv39IsAccepted() throws MemoryAccessException {
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
        assertEquals(R5.SATP_MODE_NONE, writeThenReadSatp(R5.SATP_MODE_NONE));
    }

    @Test
    public void writeBareFromResetIsAccepted() throws MemoryAccessException {
        assertEquals(R5.SATP_MODE_NONE, writeThenReadSatp(R5.SATP_MODE_NONE));
    }

    @Test
    public void writeUnsupportedModeIsIgnored() throws MemoryAccessException {
        // We only do Sv39 and Sv48 for now. Spec says unsupported MODE writes leave satp
        // unchanged, so check for that.
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV57 | PPN));
    }

    @Test
    public void asidIsMaskedOff() throws MemoryAccessException {
        // ASID is not implemented and is masked off on write.
        final long withAsid = R5.SATP_MODE_SV39 | R5.SATP_ASID_MASK64 | PPN;
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(withAsid));
    }

    private long writeThenReadSatp(final long value) throws MemoryAccessException {
        store(PHYSICAL_MEMORY_START, csrrw(0, R5CSR.SATP, 1));
        store(PHYSICAL_MEMORY_START + 4, csrrs(2, R5CSR.SATP, 0));

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = value;
        registers[2] = 0;

        cpu.getDebugInterface().setProgramCounter(PHYSICAL_MEMORY_START);
        cpu.getDebugInterface().step();
        cpu.getDebugInterface().step();

        return registers[2];
    }

    private void store(final long address, final int instruction) throws MemoryAccessException {
        memoryMap.store(address, instruction, Sizes.SIZE_32_LOG2);
    }
}
