package li.cil.sedna.device.serial;

import li.cil.sedna.api.Sizes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public final class UART16550ATests {
    private static final int UART_THR_OFFSET = 0;
    private static final int UART_FCR_OFFSET = 2;

    private static final int UART_FCR_FE = 1 << 0;

    private UART16550A uart;

    @BeforeEach
    public void setUp() {
        uart = new UART16550A();
    }

    // ------------------------------------------------------------- //

    @Test
    public void readReturnsUnsignedBytes() {
        enableFifo();
        guestWrites((byte) 0xFF, (byte) 0x80, (byte) 0x7F);

        assertEquals(0xFF, uart.read(), "a 0xFF byte must not read as end of data");
        assertEquals(0x80, uart.read());
        assertEquals(0x7F, uart.read());
        assertEquals(-1, uart.read(), "and only then is there nothing left");
    }

    @Test
    public void batchReadDrainsTheTransmitFifo() {
        enableFifo();
        guestWrites("hello".getBytes(StandardCharsets.UTF_8));

        final ByteBuffer dst = ByteBuffer.allocate(16);
        assertEquals(5, uart.read(dst));
        assertEquals("hello", asString(dst));

        assertEquals(0, uart.read(ByteBuffer.allocate(16)), "nothing is left");
    }

    @Test
    public void batchReadCarriesHighBytesThrough() {
        enableFifo();
        guestWrites((byte) 0x00, (byte) 0x80, (byte) 0xFF, (byte) 0x41);

        final byte[] dst = new byte[8];
        assertEquals(4, uart.read(dst, 0, dst.length),
                "a high byte must not truncate the batch either");
        assertArrayEquals(new byte[]{0x00, (byte) 0x80, (byte) 0xFF, 0x41, 0, 0, 0, 0}, dst);
    }

    @Test
    public void batchReadStopsAtTheDestinationLimitAndKeepsOrder() {
        enableFifo();
        guestWrites("abcde".getBytes(StandardCharsets.UTF_8));

        final ByteBuffer first = ByteBuffer.allocate(2);
        assertEquals(2, uart.read(first));
        assertEquals("ab", asString(first));

        assertEquals('c', uart.read());

        final ByteBuffer rest = ByteBuffer.allocate(16);
        assertEquals(2, uart.read(rest));
        assertEquals("de", asString(rest));
    }

    @Test
    public void batchReadWorksWithoutTheFifo() {
        // No FCR write, so the UART holds a single byte in THR.
        guestWrites((byte) 'x');

        final ByteBuffer dst = ByteBuffer.allocate(16);
        assertEquals(1, uart.read(dst), "the holding register is all there is to read");
        assertEquals("x", asString(dst));

        assertEquals(0, uart.read(ByteBuffer.allocate(16)));
    }

    @Test
    public void batchReadReturnsZeroWhenIdle() {
        enableFifo();

        assertEquals(0, uart.read(ByteBuffer.allocate(16)), "nothing was written");

        guestWrites((byte) 'q');
        final ByteBuffer full = ByteBuffer.allocate(4);
        full.position(full.limit());
        assertEquals(0, uart.read(full), "a destination with no room reads nothing");
        assertEquals('q', uart.read(), "and leaves the data in place");
    }

    // ------------------------------------------------------------- //

    private void enableFifo() {
        uart.store(UART_FCR_OFFSET, UART_FCR_FE, Sizes.SIZE_8_LOG2);
    }

    private void guestWrites(final byte... values) {
        for (final byte value : values) {
            uart.store(UART_THR_OFFSET, value, Sizes.SIZE_8_LOG2);
        }
    }

    private static String asString(final ByteBuffer buffer) {
        final ByteBuffer view = buffer.duplicate();
        view.flip();
        final byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
