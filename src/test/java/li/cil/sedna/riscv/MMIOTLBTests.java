package li.cil.sedna.riscv;

import li.cil.sedna.api.device.MemoryMappedDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class MMIOTLBTests {
    private static final int RAM_SIZE = 16 * 1024 * 1024;

    private static final int MEGAPAGE = 2 * 1024 * 1024;

    private static final long CODE = Vm.RAM_START;

    private static final long DEVICE_PAGE = 0x10000000L;
    private static final int DEVICE_LENGTH = 0x100;
    private static final long DEVICE_A_ADDRESS = DEVICE_PAGE;
    private static final long DEVICE_B_ADDRESS = DEVICE_PAGE + DEVICE_LENGTH;
    private static final long HOLE_ADDRESS = DEVICE_PAGE + 0x800;

    private static final long REMAP_SOURCE = 0x12000000L;
    private static final long REMAP_TARGET = REMAP_SOURCE + MEGAPAGE;

    private static final long SHARED_ADDRESS = Vm.RAM_START + MEGAPAGE;

    private static final long ROOT_TABLE = Vm.RAM_START + 3 * MEGAPAGE;
    private static final long LEVEL1_TABLE = ROOT_TABLE + 0x1000;

    private Vm vm;
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
    public void setUp() {
        vm = Vm.create(RAM_SIZE);

        deviceA = new TestDevice(0x1111222233334444L);
        deviceB = new TestDevice(0x5555666677778888L);
        vm.addDevice(DEVICE_A_ADDRESS, deviceA);
        vm.addDevice(DEVICE_B_ADDRESS, deviceB);

        vm.writeCSR(R5CSR.MTVEC, CODE);
    }

    @Test
    public void devicesSharingAPageEachReceiveTheirOwnAccesses() {
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
    public void unmappedHoleSharingACachedDevicePageStillFaults() {
        assertEquals(deviceA.loadValue, read(DEVICE_A_ADDRESS));

        read(HOLE_ADDRESS);

        assertEquals(R5.EXCEPTION_FAULT_LOAD, vm.readCSR(R5CSR.MCAUSE),
            "an access outside the device but inside its cached page must still fault");
    }

    @Test
    public void deviceChangesAreVisibleAfterCacheInvalidation() {
        assertEquals(deviceA.loadValue, read(DEVICE_A_ADDRESS));

        // Contract: memory map changes require invalidateCaches(), same as for RAM.
        vm.memoryMap().removeDevice(deviceA);
        final TestDevice replacement = new TestDevice(0x0BADC0DE0BADC0DEL);
        vm.addDevice(DEVICE_A_ADDRESS, replacement);
        vm.cpu().invalidateCaches();

        assertEquals(replacement.loadValue, read(DEVICE_A_ADDRESS));
    }

    @Test
    public void sfenceInvalidatesDeviceTranslations() {
        final TestDevice source = new TestDevice(0x00000000CAFEBABEL);
        final TestDevice target = new TestDevice(0x00000000DEADBEEFL);
        vm.addDevice(REMAP_SOURCE, source);
        vm.addDevice(REMAP_TARGET, target);

        vm.store64(ROOT_TABLE + Vm.pageTableIndex(CODE, 2) * 8L, Vm.pointerPTE(LEVEL1_TABLE));
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(CODE, 1) * 8L, Vm.leafPTE(Vm.RAM_START));
        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(SHARED_ADDRESS, 1) * 8L, Vm.leafPTE(REMAP_SOURCE));
        vm.enterSupervisor(Vm.satpSv39(ROOT_TABLE), CODE);

        assertEquals(source.loadValue, read(SHARED_ADDRESS));

        vm.store64(LEVEL1_TABLE + Vm.pageTableIndex(SHARED_ADDRESS, 1) * 8L, Vm.leafPTE(REMAP_TARGET));
        vm.flushPage(SHARED_ADDRESS);

        assertEquals(target.loadValue, read(SHARED_ADDRESS),
            "the remapped page must resolve to the new device");
    }

    // ------------------------------------------------------------- //

    private long read(final long address) {
        final long[] registers = vm.registers();
        registers[1] = address;
        registers[2] = 0;
        vm.execute(ld(2, 1, 0));
        return registers[2];
    }

    private void write(final long address, final long value) {
        final long[] registers = vm.registers();
        registers[1] = address;
        registers[2] = value;
        vm.execute(sd(2, 1, 0));
    }
}
