package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class MMIOTLBTests {
    private static final long PHYSICAL_MEMORY_START = 0x80000000L;
    private static final int PHYSICAL_MEMORY_LENGTH = 16 * 1024 * 1024;

    private static final int MEGAPAGE = 2 * 1024 * 1024;

    private static final long CODE = PHYSICAL_MEMORY_START;

    private static final long DEVICE_PAGE = 0x10000000L;
    private static final int DEVICE_LENGTH = 0x100;
    private static final long DEVICE_A_ADDRESS = DEVICE_PAGE;
    private static final long DEVICE_B_ADDRESS = DEVICE_PAGE + DEVICE_LENGTH;
    private static final long HOLE_ADDRESS = DEVICE_PAGE + 0x800;

    private static final long REMAP_SOURCE = 0x12000000L;
    private static final long REMAP_TARGET = REMAP_SOURCE + MEGAPAGE;

    private static final long SHARED_ADDRESS = PHYSICAL_MEMORY_START + MEGAPAGE;

    private static final long ROOT_TABLE = PHYSICAL_MEMORY_START + 3 * MEGAPAGE;
    private static final long LEVEL1_TABLE = ROOT_TABLE + 0x1000;

    private MemoryMap memoryMap;
    private R5CPU cpu;
    private TestDevice deviceA;
    private TestDevice deviceB;

    private static final class TestDevice implements MemoryMappedDevice {
        public long loadValue;
        public long storedValue;
        public int storedOffset = -1;
        public int loadCount;

        TestDevice(final long loadValue) {
            this.loadValue = loadValue;
        }

        @Override
        public int getLength() {
            return DEVICE_LENGTH;
        }

        @Override
        public long load(final int offset, final int sizeLog2) {
            loadCount++;
            return loadValue;
        }

        @Override
        public void store(final int offset, final long value, final int sizeLog2) {
            storedOffset = offset;
            storedValue = value;
        }
    }

    @BeforeEach
    public void setUp() throws MemoryAccessException {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(PHYSICAL_MEMORY_START, Memory.create(PHYSICAL_MEMORY_LENGTH));

        deviceA = new TestDevice(0x1111222233334444L);
        deviceB = new TestDevice(0x5555666677778888L);
        memoryMap.addDevice(DEVICE_A_ADDRESS, deviceA);
        memoryMap.addDevice(DEVICE_B_ADDRESS, deviceB);

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, CODE);
        cpu.setXLEN(R5.XLEN_64);

        writeCSR(R5CSR.MTVEC, CODE);
    }

    @Test
    public void devicesSharingAPageEachReceiveTheirOwnAccesses() throws MemoryAccessException {
        // Repeated and alternating, so later rounds run against a populated cache.
        for (int i = 0; i < 4; i++) {
            assertEquals(deviceA.loadValue, read(DEVICE_A_ADDRESS), "device A, round " + i);
            assertEquals(deviceB.loadValue, read(DEVICE_B_ADDRESS), "device B, round " + i);
        }
        assertEquals(4, deviceA.loadCount, "every guest load must reach device A");
        assertEquals(4, deviceB.loadCount, "every guest load must reach device B");

        write(DEVICE_A_ADDRESS + 8, 0xAAL);
        write(DEVICE_B_ADDRESS + 16, 0xBBL);
        assertEquals(8, deviceA.storedOffset);
        assertEquals(0xAAL, deviceA.storedValue);
        assertEquals(16, deviceB.storedOffset);
        assertEquals(0xBBL, deviceB.storedValue);
    }

    @Test
    public void unmappedHoleSharingACachedDevicePageStillFaults() throws MemoryAccessException {
        assertEquals(deviceA.loadValue, read(DEVICE_A_ADDRESS));

        read(HOLE_ADDRESS);

        assertEquals(R5.EXCEPTION_FAULT_LOAD, readCSR(R5CSR.MCAUSE),
            "an access outside the device but inside its cached page must still fault");
    }

    @Test
    public void deviceChangesAreVisibleAfterCacheInvalidation() throws MemoryAccessException {
        assertEquals(deviceA.loadValue, read(DEVICE_A_ADDRESS));

        // Contract: memory map changes require invalidateCaches(), same as for RAM.
        memoryMap.removeDevice(deviceA);
        final TestDevice replacement = new TestDevice(0x0BADC0DE0BADC0DEL);
        memoryMap.addDevice(DEVICE_A_ADDRESS, replacement);
        cpu.invalidateCaches();

        assertEquals(replacement.loadValue, read(DEVICE_A_ADDRESS));
    }

    @Test
    public void sfenceInvalidatesDeviceTranslations() throws MemoryAccessException {
        final TestDevice source = new TestDevice(0x00000000CAFEBABEL);
        final TestDevice target = new TestDevice(0x00000000DEADBEEFL);
        memoryMap.addDevice(REMAP_SOURCE, source);
        memoryMap.addDevice(REMAP_TARGET, target);

        memoryMap.store(ROOT_TABLE + index(CODE, 2) * 8L, pointerPTE(LEVEL1_TABLE), Sizes.SIZE_64_LOG2);
        memoryMap.store(LEVEL1_TABLE + index(CODE, 1) * 8L, leafPTE(PHYSICAL_MEMORY_START), Sizes.SIZE_64_LOG2);
        memoryMap.store(LEVEL1_TABLE + index(SHARED_ADDRESS, 1) * 8L, leafPTE(REMAP_SOURCE), Sizes.SIZE_64_LOG2);
        enterSupervisor();

        assertEquals(source.loadValue, read(SHARED_ADDRESS));

        memoryMap.store(LEVEL1_TABLE + index(SHARED_ADDRESS, 1) * 8L, leafPTE(REMAP_TARGET), Sizes.SIZE_64_LOG2);
        flushPage(SHARED_ADDRESS);

        assertEquals(target.loadValue, read(SHARED_ADDRESS),
            "the remapped page must resolve to the new device");
    }

    ///////////////////////////////////////////////////////////////////

    private long read(final long address) throws MemoryAccessException {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = address;
        registers[2] = 0;
        execute(ld(2, 1, 0));
        return registers[2];
    }

    private void write(final long address, final long value) throws MemoryAccessException {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = address;
        registers[2] = value;
        execute(sd(2, 1, 0));
    }

    private void enterSupervisor() throws MemoryAccessException {
        writeCSR(R5CSR.SATP, R5.SATP_MODE_SV39 | (ROOT_TABLE >>> R5.PAGE_ADDRESS_SHIFT));
        setCSRBits(R5CSR.MSTATUS, (long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);
        writeCSR(R5CSR.MEPC, CODE);
        execute(MRET);
    }

    /** Issues {@code sfence.vma rs1, x0}, invalidating the translations for one page. */
    private void flushPage(final long address) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[3] = address;
        execute(sfenceVma(3));
    }

    private void writeCSR(final int csr, final long value) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[31] = value;
        execute(csrrw(0, csr, 31));
    }

    private long readCSR(final int csr) throws MemoryAccessException {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[31] = 0;
        execute(csrrs(31, csr, 0));
        return registers[31];
    }

    private void setCSRBits(final int csr, final long mask) throws MemoryAccessException {
        cpu.getDebugInterface().getGeneralRegisters()[31] = mask;
        execute(csrrs(0, csr, 31));
    }

    private void execute(final int instruction) throws MemoryAccessException {
        memoryMap.store(CODE, instruction, Sizes.SIZE_32_LOG2);
        cpu.getDebugInterface().setProgramCounter(CODE);
        cpu.getDebugInterface().step();
    }

    private static int index(final long address, final int level) {
        return (int) ((address >>> (R5.PAGE_ADDRESS_SHIFT + 9 * level)) & 0x1FF);
    }

    private static long pointerPTE(final long table) {
        return ((table >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | R5.PTE_V_MASK;
    }

    private static long leafPTE(final long page) {
        return ((page >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS)
            | R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK | R5.PTE_X_MASK
            | R5.PTE_A_MASK | R5.PTE_D_MASK;
    }
}
