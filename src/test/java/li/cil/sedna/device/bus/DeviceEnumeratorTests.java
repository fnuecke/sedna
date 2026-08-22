package li.cil.sedna.device.bus;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.device.bus.DeviceClass;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.DeviceWindow;
import li.cil.sedna.device.disk.WD1793;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.z80.Z80Board;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DeviceEnumeratorTests {
    private static final int BUS_PORT = 0x40;
    private static final int UART_PORT = 0x20;
    private static final int FDC_PORT = 0x10;

    private static final int REG_VERSION = 0;
    private static final int REG_COUNT = 1;
    private static final int REG_SELECT = 2;
    private static final int REG_CLASS = 3;
    private static final int REG_PORT = 5;
    private static final int REG_NAME = 7;
    private static final int REG_RESERVED = 15;

    private Z80Board board;
    private PhysicalMemory memory;
    private DeviceEnumerator enumerator;

    @BeforeEach
    public void setupEach() {
        Sedna.initialize();

        board = new Z80Board();
        memory = Memory.create(0x10000);
        assertTrue(board.addDevice(0, memory));

        enumerator = new DeviceEnumerator(board.getPortMap(), board.getDevices(),
            board.getInterruptController());
        assertTrue(board.addPortDevice(BUS_PORT, enumerator));
        assertTrue(board.addPortDevice(UART_PORT, new DeviceWindow(new UART16550A(), 8)));
        assertTrue(board.addPortDevice(FDC_PORT, new WD1793()));
    }

    @Test
    public void reportsItsFormatVersionAndZeroForReservedRegisters() {
        assertEquals(DeviceEnumerator.VERSION, read(REG_VERSION));
        assertEquals(0, read(REG_RESERVED));
    }

    @Test
    public void aWindowedControllerStillReportsEveryDrive() {
        final WD1793 multi = new WD1793();
        multi.setUnitCount(3);
        assertTrue(board.addPortDevice(0x60, new DeviceWindow(multi, 5)));

        select(0);
        assertEquals(6, read(REG_COUNT)); // bus + uart + floppy + three more drives
    }

    @Test
    public void memoryMappedDevicesAreNotEnumerated() {
        board.removeDevice(memory); // the RAM covers the whole address space, leaving nowhere to map
        assertTrue(board.addDevice(0x9000, new WD1793()));

        select(0);
        assertEquals(3, read(REG_COUNT));
    }

    @Test
    public void enumeratorIsAlwaysTheFirstEntry() {
        select(0);
        assertEquals(3, read(REG_COUNT));
        assertEquals(DeviceClass.BUS.value(), read(REG_CLASS));
        assertEquals("SEDBUS", readName());
    }

    @Test
    public void devicesFollowInInsertionOrder() {
        select(1);
        assertEquals(DeviceClass.CHARACTER.value(), read(REG_CLASS));
        assertEquals("UART", readName());

        select(2);
        assertEquals(DeviceClass.BLOCK.value(), read(REG_CLASS));
        assertEquals("FLOPPY", readName());
    }

    @Test
    public void reportsThePortEachDeviceWasMappedAt() {
        select(0);
        assertEquals(BUS_PORT, read(REG_PORT));
        select(1);
        assertEquals(UART_PORT, read(REG_PORT));
        select(2);
        assertEquals(FDC_PORT, read(REG_PORT));
    }

    @Test
    public void movingADeviceMovesItsReportedPort() {
        final MemoryMappedDevice fdc = board.getDevices().get(3);
        board.removeDevice(fdc);
        assertTrue(board.addPortDevice(0x60, fdc));

        select(2);
        assertEquals(0x60, read(REG_PORT));
    }

    @Test
    public void undescribedDevicesAreNotEnumerated() {
        assertTrue(board.addPortDevice(0x70, new UndescribedDevice()));
        select(0);
        assertEquals(3, read(REG_COUNT));
    }

    @Test
    public void selectingOutOfRangeReportsNothing() {
        select(9);
        assertEquals(DeviceClass.UNKNOWN.value(), read(REG_CLASS));
        assertEquals(0xFF, read(REG_PORT));
    }

    @Test
    public void nameStreamRewindsOnWrite() {
        select(1);
        assertEquals('U', read(REG_NAME));
        assertEquals('A', read(REG_NAME));
        enumerator.store(REG_NAME, 0, Sizes.SIZE_8_LOG2);
        assertEquals('U', read(REG_NAME));
    }

    @Test
    public void selectionSurvivesSerialization() {
        select(2);
        read(REG_NAME);

        final ByteBuffer serialized = BinarySerialization.serialize(enumerator);

        final Z80Board other = new Z80Board();
        final DeviceEnumerator restored = new DeviceEnumerator(other.getPortMap(), other.getDevices(),
            other.getInterruptController());
        BinarySerialization.deserialize(serialized, restored);

        assertEquals(2, restored.load(REG_SELECT, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void guestFindsADeviceThroughTheWindow() throws MemoryAccessException {
        load(0x0000,
            0xDB, BUS_PORT + REG_COUNT,  // IN A,(count)
            0x32, 0x00, 0x80,            // LD (0x8000),A
            0x3E, 0x02,                  // LD A,2
            0xD3, BUS_PORT + REG_SELECT, // OUT (select),A
            0xDB, BUS_PORT + REG_CLASS,  // IN A,(class)
            0x32, 0x01, 0x80,            // LD (0x8001),A
            0xDB, BUS_PORT + REG_PORT,   // IN A,(port)
            0x32, 0x02, 0x80,            // LD (0x8002),A
            0x76);                       // HALT

        board.getCpu().reset(true, 0x0000);
        board.setRunning(true);
        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(1_000);
        }
        assertTrue(board.isHalted());

        assertEquals(3, memory.load(0x8000, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(DeviceClass.BLOCK.value(), memory.load(0x8001, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(FDC_PORT, memory.load(0x8002, Sizes.SIZE_8_LOG2) & 0xFF);
    }

    private void select(final int index) {
        enumerator.store(REG_SELECT, index, Sizes.SIZE_8_LOG2);
    }

    private int read(final int register) {
        return (int) enumerator.load(register, Sizes.SIZE_8_LOG2);
    }

    private String readName() {
        enumerator.store(REG_NAME, 0, Sizes.SIZE_8_LOG2);
        final StringBuilder name = new StringBuilder();
        for (int value; (value = read(REG_NAME)) != 0; ) {
            name.append((char) value);
        }
        return name.toString();
    }

    private void load(final int address, final int... program) throws MemoryAccessException {
        for (int i = 0; i < program.length; i++) {
            memory.store(address + i, program[i], Sizes.SIZE_8_LOG2);
        }
    }

    private static final class UndescribedDevice implements MemoryMappedDevice {
        @Override
        public int getLength() {
            return 1;
        }

        @Override
        public long load(final int offset, final int sizeLog2) {
            return 0;
        }

        @Override
        public void store(final int offset, final long value, final int sizeLog2) {
        }
    }
}
