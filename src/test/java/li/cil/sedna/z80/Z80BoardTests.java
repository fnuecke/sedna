package li.cil.sedna.z80;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.DeviceWindow;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.disk.WD1793;
import li.cil.sedna.device.flash.FlashMemoryDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public final class Z80BoardTests {
    private static final int UART_PORT = 0x20;
    private static final int FDC_PORT = 0x10;
    private static final int LATCH_PORT = 0x30;
    private static final int ROM_SIZE = 0x100;

    private static final int SIDES = 1, TRACKS = 4, SECTORS = 8, SECTOR_SIZE = 128;

    private Z80Board board;
    private PhysicalMemory memory;

    @BeforeEach
    public void setupEach() {
        board = new Z80Board();
        memory = Memory.create(0x10000);
        assertTrue(board.addDevice(0, memory));
    }

    @Test
    public void uartEchoThroughPorts() throws MemoryAccessException {
        final UART16550A uart = new UART16550A();
        assertTrue(board.addPortDevice(UART_PORT, new DeviceWindow(uart, 8)));

        // Poll LSR for received data, echo it back, halt on a zero byte.
        load(0x0000,
                0xDB, UART_PORT + 5, // loop: IN A,(LSR)
                0xE6, 0x01,          //       AND 1
                0x28, 0xFA,          //       JR Z, loop
                0xDB, UART_PORT,     //       IN A,(RBR)
                0xB7,                //       OR A
                0x28, 0x04,          //       JR Z, done
                0xD3, UART_PORT,     //       OUT (THR),A
                0x18, 0xF1,          //       JR loop
                0x76);               // done: HALT

        board.getCpu().reset(true, 0x0000);
        board.setRunning(true);

        // The FIFO-less UART holds a single byte per direction, so feed and drain one at a time.
        final StringBuilder echoed = new StringBuilder();
        for (final byte value : "HELLO".getBytes()) {
            uart.putByte(value);
            for (int i = 0; i < 100; i++) {
                board.step(1_000);
                final int read = uart.read();
                if (read >= 0) {
                    echoed.append((char) read);
                    break;
                }
            }
        }
        assertEquals("HELLO", echoed.toString());

        uart.putByte((byte) 0);
        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(1_000);
        }
        assertTrue(board.isHalted());
    }

    @Test
    public void floppyReadSectorFromGuest() throws Exception {
        final ByteBufferBlockDevice disk = ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, false);
        final WD1793 fdc = new WD1793();
        fdc.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);
        assertTrue(board.addPortDevice(FDC_PORT, fdc));

        // Pattern into track 1, sector 3 (0-based): each byte = its index xor 0x5A.
        final byte[] sector = new byte[SECTOR_SIZE];
        for (int i = 0; i < sector.length; i++) {
            sector[i] = (byte) (i ^ 0x5A);
        }
        disk.getOutputStream(((1 * SECTORS) + 3) * SECTOR_SIZE).write(sector);

        // Seek to track 1, read sector 4 (1-based register) into 0x8000.
        load(0x0000,
                0xAF,                // XOR A
                0xD3, FDC_PORT + 4,  // OUT (system),A  -- drive 0, side 0
                0x3E, 0x01,          // LD A,1
                0xD3, FDC_PORT + 3,  // OUT (data),A    -- seek target
                0x3E, 0x10,          // LD A,0x10 (SEEK)
                0xD3, FDC_PORT,      // OUT (command),A
                0x3E, 0x04,          // LD A,4
                0xD3, FDC_PORT + 2,  // OUT (sector),A
                0x3E, 0x80,          // LD A,0x80 (READ SECTOR)
                0xD3, FDC_PORT,      // OUT (command),A
                0x21, 0x00, 0x80,    // LD HL,0x8000
                0x06, SECTOR_SIZE,   // LD B,128
                0xDB, FDC_PORT + 3,  // loop: IN A,(data)
                0x77,                //       LD (HL),A
                0x23,                //       INC HL
                0x10, 0xFA,          //       DJNZ loop
                0x76);               // HALT

        board.getCpu().reset(true, 0x0000);
        board.setRunning(true);
        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(10_000);
        }
        assertTrue(board.isHalted());

        for (int i = 0; i < SECTOR_SIZE; i++) {
            assertEquals(sector[i] & 0xFF, (int) board.getMemoryMap().load(0x8000 + i, Sizes.SIZE_8_LOG2) & 0xFF, "byte " + i);
        }
    }

    @Test
    public void floppyWriteSectorRoundTrip() throws Exception {
        final ByteBufferBlockDevice disk = ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, false);
        final WD1793 fdc = new WD1793();
        fdc.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);

        // Host-side register poking: write a pattern to track 0, sector 1, read it back.
        fdc.store(2, 1, Sizes.SIZE_8_LOG2); // sector register (1-based)
        fdc.store(0, 0xA0, Sizes.SIZE_8_LOG2); // WRITE SECTOR
        for (int i = 0; i < SECTOR_SIZE; i++) {
            fdc.store(3, i * 7, Sizes.SIZE_8_LOG2);
        }
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2)); // IRQ set, transfer complete.
        assertEquals(0, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x03); // Not busy, no DRQ.

        fdc.store(2, 1, Sizes.SIZE_8_LOG2);
        fdc.store(0, 0x80, Sizes.SIZE_8_LOG2); // READ SECTOR
        for (int i = 0; i < SECTOR_SIZE; i++) {
            assertEquals((i * 7) & 0xFF, (int) fdc.load(3, Sizes.SIZE_8_LOG2), "byte " + i);
        }
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void floppyReportsMissingDisk() {
        final WD1793 fdc = new WD1793();
        fdc.store(0, 0x80, Sizes.SIZE_8_LOG2); // READ SECTOR without media.
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2)); // IRQ raised.
        assertEquals(0x90, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x90); // Record-not-found + not-ready.
    }

    @Test
    public void floppyStepCommandsSeekAndComplete() {
        final WD1793 fdc = newFloppy();

        fdc.store(0, 0x40 | 0x10, Sizes.SIZE_8_LOG2); // STEP IN with update.
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2)); // IRQ, command complete.
        assertEquals(0, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x01); // Not busy.
        assertEquals(1, fdc.load(1, Sizes.SIZE_8_LOG2));

        fdc.store(0, 0x20 | 0x10, Sizes.SIZE_8_LOG2); // STEP repeats the last direction.
        assertEquals(2, fdc.load(1, Sizes.SIZE_8_LOG2));
        assertEquals(0, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x01);

        fdc.store(0, 0x60 | 0x10, Sizes.SIZE_8_LOG2); // STEP OUT with update.
        fdc.store(0, 0x20 | 0x10, Sizes.SIZE_8_LOG2); // STEP again, now outward.
        assertEquals(0, fdc.load(1, Sizes.SIZE_8_LOG2));
        assertEquals(0x04, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x05); // Track 0, not busy.
    }

    @Test
    public void floppySeekValidatesTarget() {
        final WD1793 fdc = newFloppy();

        fdc.store(3, TRACKS, Sizes.SIZE_8_LOG2); // Data register holds the (invalid) target.
        fdc.store(0, 0x10, Sizes.SIZE_8_LOG2); // SEEK
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2)); // IRQ raised, not wedged.
        assertEquals(0x10, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x11); // Seek error, not busy.
        assertEquals(0, fdc.load(1, Sizes.SIZE_8_LOG2)); // Track register unchanged.
    }

    @Test
    public void floppyForceInterruptWhenIdleReportsTrackZero() {
        final WD1793 fdc = newFloppy();

        fdc.store(0, 0xD8, Sizes.SIZE_8_LOG2); // FORCE INTERRUPT with immediate IRQ, idle.
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2));
        assertEquals(0x04, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x05); // Track 0 reported, not busy.
    }

    @Test
    public void floppyMultiSectorReadCrossesSectorBoundaries() throws Exception {
        final ByteBufferBlockDevice disk = ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, false);
        final byte[] track0 = new byte[SECTORS * SECTOR_SIZE];
        for (int i = 0; i < track0.length; i++) {
            track0[i] = (byte) (i * 31);
        }
        disk.getOutputStream(0).write(track0);

        final WD1793 fdc = new WD1793();
        fdc.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);

        fdc.store(2, 3, Sizes.SIZE_8_LOG2); // Start at sector 3 (1-based).
        fdc.store(0, 0x90, Sizes.SIZE_8_LOG2); // READ SECTOR MULTIPLE, to end of track.
        for (int i = 2 * SECTOR_SIZE; i < track0.length; i++) {
            assertEquals(track0[i] & 0xFF, (int) fdc.load(3, Sizes.SIZE_8_LOG2), "byte " + i);
        }
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2));
        assertEquals(SECTORS, fdc.load(2, Sizes.SIZE_8_LOG2)); // Sector register on the last sector.
    }

    @Test
    public void floppyWriteProtectReported() throws Exception {
        final ByteBufferBlockDevice disk = ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, true);
        final WD1793 fdc = new WD1793();
        fdc.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);

        fdc.store(0, 0xA0, Sizes.SIZE_8_LOG2); // WRITE SECTOR on readonly media.
        assertEquals(0x80, fdc.load(4, Sizes.SIZE_8_LOG2));
        assertEquals(0x40, fdc.load(0, Sizes.SIZE_8_LOG2) & 0x41); // Write protect, not busy.
    }

    @Test
    public void floppySerializationResumesMidTransfer() throws Exception {
        Sedna.initialize();

        final ByteBufferBlockDevice disk = ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, false);
        final byte[] sector = new byte[SECTOR_SIZE];
        for (int i = 0; i < sector.length; i++) {
            sector[i] = (byte) (i + 1);
        }
        disk.getOutputStream(0).write(sector);

        final WD1793 fdc = new WD1793();
        fdc.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);

        fdc.store(2, 1, Sizes.SIZE_8_LOG2);
        fdc.store(0, 0x80, Sizes.SIZE_8_LOG2); // READ SECTOR
        for (int i = 0; i < SECTOR_SIZE / 2; i++) {
            assertEquals(sector[i] & 0xFF, (int) fdc.load(3, Sizes.SIZE_8_LOG2));
        }

        final ByteBuffer serialized = BinarySerialization.serialize(fdc);
        final WD1793 restored = new WD1793();
        restored.setDisk(disk, SIDES, TRACKS, SECTORS, SECTOR_SIZE);
        BinarySerialization.deserialize(serialized, restored);

        for (int i = SECTOR_SIZE / 2; i < SECTOR_SIZE; i++) {
            assertEquals(sector[i] & 0xFF, (int) restored.load(3, Sizes.SIZE_8_LOG2), "byte " + i);
        }
        assertEquals(0x80, restored.load(4, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void bootRomShadowsRamAtReset() throws MemoryAccessException {
        final FlashMemoryDevice rom = new FlashMemoryDevice(ROM_SIZE);
        loadRom(rom, 0x0000,
                0x3E, 0xA5,          // LD A,0xA5
                0x32, 0x00, 0x80,    // LD (0x8000),A
                0x76);               // HALT
        board.setBootRom(rom);

        // The same addresses in RAM hold a program writing a different marker; it must not run.
        load(0x0000,
                0x3E, 0x5A,          // LD A,0x5A
                0x32, 0x00, 0x80,    // LD (0x8000),A
                0x76);               // HALT

        run(0x0000);

        assertEquals(0xA5, memory.load(0x8000, Sizes.SIZE_8_LOG2) & 0xFF);
    }

    @Test
    public void clearingLatchUnmapsBootRom() throws MemoryAccessException {
        final FlashMemoryDevice rom = new FlashMemoryDevice(ROM_SIZE);
        loadRom(rom, 0x0000,
                0xC3, 0x00, 0x80);   // JP 0x8000
        board.setBootRom(rom);
        assertTrue(board.addPortDevice(LATCH_PORT, new BootRomLatch(board)));

        memory.store(0x0000, 0x5A, Sizes.SIZE_8_LOG2);

        // Runs above the ROM, so clearing the latch does not pull the code out from under it.
        load(0x8000,
                0x3A, 0x00, 0x00,    // LD A,(0x0000)   -- boot ROM
                0x32, 0x00, 0x81,    // LD (0x8100),A
                0xAF,                // XOR A
                0xD3, LATCH_PORT,    // OUT (LATCH),A   -- unmap
                0x3A, 0x00, 0x00,    // LD A,(0x0000)   -- RAM
                0x32, 0x01, 0x81,    // LD (0x8101),A
                0x76);               // HALT

        run(0x0000);

        assertEquals(0xC3, memory.load(0x8100, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(0x5A, memory.load(0x8101, Sizes.SIZE_8_LOG2) & 0xFF);
        assertFalse(board.isBootRomMapped());
    }

    @Test
    public void latchReadsBackStateAndResetRemapsBootRom() throws MemoryAccessException {
        final FlashMemoryDevice rom = new FlashMemoryDevice(ROM_SIZE);
        board.setBootRom(rom);
        final BootRomLatch latch = new BootRomLatch(board);
        assertTrue(board.addPortDevice(LATCH_PORT, latch));

        assertEquals(1, latch.load(0, Sizes.SIZE_8_LOG2));

        latch.store(0, 0, Sizes.SIZE_8_LOG2);
        assertFalse(board.isBootRomMapped());
        assertEquals(0, latch.load(0, Sizes.SIZE_8_LOG2));

        board.reset();
        assertTrue(board.isBootRomMapped());
    }

    @Test
    public void bootRomWritesAreDroppedWhileMapped() throws MemoryAccessException {
        final FlashMemoryDevice rom = new FlashMemoryDevice(ROM_SIZE);
        loadRom(rom, 0x0000, 0xC3);
        board.setBootRom(rom);

        board.getMemoryMap().store(0x0000, 0x11, Sizes.SIZE_8_LOG2);

        assertEquals(0xC3, board.getMemoryMap().load(0x0000, Sizes.SIZE_8_LOG2) & 0xFF);
        assertEquals(0x00, memory.load(0x0000, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void bootRomLatchStateSurvivesSerialization() {
        Sedna.initialize();

        final FlashMemoryDevice rom = new FlashMemoryDevice(ROM_SIZE);
        board.setBootRom(rom);
        board.setBootRomMapped(false);

        final ByteBuffer serialized = BinarySerialization.serialize(board);

        final Z80Board restored = new Z80Board();
        assertTrue(restored.addDevice(0, Memory.create(0x10000)));
        restored.setBootRom(new FlashMemoryDevice(ROM_SIZE));
        assertTrue(restored.isBootRomMapped());

        BinarySerialization.deserialize(serialized, restored);

        assertFalse(restored.isBootRomMapped());
    }

    private void run(final int pc) {
        board.getCpu().reset(true, pc);
        board.setRunning(true);
        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(1_000);
        }
        assertTrue(board.isHalted());
    }

    private static void loadRom(final FlashMemoryDevice rom, final int address, final int... program) {
        for (int i = 0; i < program.length; i++) {
            rom.getData().put(address + i, (byte) program[i]);
        }
    }

    private static WD1793 newFloppy() {
        final WD1793 fdc = new WD1793();
        fdc.setDisk(ByteBufferBlockDevice.create(SIDES * TRACKS * SECTORS * SECTOR_SIZE, false), SIDES, TRACKS, SECTORS, SECTOR_SIZE);
        return fdc;
    }

    private void load(final int address, final int... program) throws MemoryAccessException {
        for (int i = 0; i < program.length; i++) {
            memory.store(address + i, program[i], Sizes.SIZE_8_LOG2);
        }
    }
}
