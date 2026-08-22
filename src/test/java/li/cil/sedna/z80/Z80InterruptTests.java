package li.cil.sedna.z80;

import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.DeviceWindow;
import li.cil.sedna.device.bus.DeviceEnumerator;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.serial.UART16550A;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Z80InterruptTests {
    private static final int UART_PORT = 0x20;
    private static final int BUS_PORT = 0xE0;

    private static final int UART_IRQ = 3;
    private static final int VECTOR = UART_IRQ << 1;

    private static final int TABLE = 0x8000;   // interrupt vector table, so I = 0x80
    private static final int HANDLER = 0x9000;
    private static final int MARKER = 0x8100;

    private Z80Board board;
    private PhysicalMemory memory;
    private UART16550A uart;
    private DeviceEnumerator enumerator;

    @BeforeEach
    public void setupEach() {
        Sedna.initialize();

        board = new Z80Board();
        memory = Memory.create(0x10000);
        assertTrue(board.addDevice(0, memory));

        uart = new UART16550A();
        assertTrue(board.addPortDevice(UART_PORT, new DeviceWindow(uart, 8)));

        enumerator = new DeviceEnumerator(board.getPortMap(), board.getDevices(),
            board.getInterruptController());
        assertTrue(board.addPortDevice(BUS_PORT, enumerator));

        uart.getInterrupt().set(UART_IRQ, board.getInterruptController());
    }

    @Test
    public void theEnumeratorReportsTheVectorTheDeviceWasWiredTo() {
        enumerator.store(2, 1, Sizes.SIZE_8_LOG2); // select the UART
        assertEquals(VECTOR, enumerator.load(6, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void anUnwiredDeviceReportsNoVector() {
        enumerator.store(2, 0, Sizes.SIZE_8_LOG2); // the enumerator itself raises nothing
        assertEquals(0xFF, enumerator.load(6, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void aGuestTakesAnInterruptThroughItsVectorTable() throws MemoryAccessException {
        load(0x0000,
            0xED, 0x5E,                     // IM 2
            0x3E, TABLE >> 8,               // LD A,80h
            0xED, 0x47,                     // LD I,A
            0x21, HANDLER & 0xFF, HANDLER >> 8,
            0x22, (TABLE + VECTOR) & 0xFF, (TABLE + VECTOR) >> 8,
            0x3E, 0x01,                     // LD A,1
            0xD3, UART_PORT + 1,            // OUT (IER),A -- interrupt on received data
            0xFB,                           // EI
            0x76);                          // HALT

        load(HANDLER,
            0x3E, 0x5A,                     // LD A,5Ah
            0x32, MARKER & 0xFF, MARKER >> 8,
            0x76);                          // HALT

        board.getCpu().reset(true, 0x0000);
        board.setRunning(true);

        // Runs up to the HALT with interrupts enabled, where isHalted() is still false.
        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(1_000);
        }
        assertEquals(0x00, memory.load(MARKER, Sizes.SIZE_8_LOG2) & 0xFF,
            "nothing should have run the handler yet");

        uart.putByte((byte) 'X');

        for (int i = 0; i < 100 && !board.isHalted(); i++) {
            board.step(1_000);
        }

        assertTrue(board.isHalted(), "the handler should have run and halted with interrupts off");
        assertEquals(0x5A, memory.load(MARKER, Sizes.SIZE_8_LOG2) & 0xFF,
            "the guest should have vectored through " + Integer.toHexString(TABLE + VECTOR));
    }

    private void load(final int address, final int... program) throws MemoryAccessException {
        for (int i = 0; i < program.length; i++) {
            memory.store(address + i, program[i], Sizes.SIZE_8_LOG2);
        }
    }
}
