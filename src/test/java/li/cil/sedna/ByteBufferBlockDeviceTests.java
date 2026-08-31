package li.cil.sedna;

import li.cil.sedna.device.block.ByteBufferBlockDevice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public final class ByteBufferBlockDeviceTests {
    private static final int THREAD_COUNT = 8;
    private static final int ITERATIONS = 2000;
    private static final int BLOCK_SIZE = 64;

    @Test
    public void streamsStartAtRequestedOffset() throws IOException {
        final ByteBufferBlockDevice device = deviceWithBlockMarkers();

        final InputStream first = device.getInputStream(BLOCK_SIZE);
        final InputStream second = device.getInputStream(2 * BLOCK_SIZE);

        assertEquals(1, first.read());
        assertEquals(2, second.read());
        assertEquals(0, device.getInputStream(0).read());
    }

    @Test
    public void concurrentInputStreamsDoNotShareStreamPosition() throws Exception {
        final ByteBufferBlockDevice device = deviceWithBlockMarkers();
        final AtomicInteger mismatches = new AtomicInteger();

        run(index -> {
            try (InputStream stream = device.getInputStream((long) index * BLOCK_SIZE)) {
                if (stream.read() != index) {
                    mismatches.incrementAndGet();
                }
            }
        });

        assertEquals(0, mismatches.get(), "concurrent input streams must not observe each other's start offset");
    }

    @Test
    public void concurrentOutputStreamsDoNotShareStreamPosition() throws Exception {
        final ByteBufferBlockDevice device = ByteBufferBlockDevice.create(THREAD_COUNT * BLOCK_SIZE, false);
        final AtomicInteger mismatches = new AtomicInteger();

        run(index -> {
            final long offset = (long) index * BLOCK_SIZE;
            try (OutputStream stream = device.getOutputStream(offset)) {
                stream.write(index);
            }
            try (InputStream stream = device.getInputStream(offset)) {
                if (stream.read() != index) {
                    mismatches.incrementAndGet();
                }
            }
        });

        assertEquals(0, mismatches.get(), "concurrent output streams must not observe each other's start offset");
    }

    // --------------------------------------------------------------------- //

    private static ByteBufferBlockDevice deviceWithBlockMarkers() {
        final ByteBuffer data = ByteBuffer.allocate(THREAD_COUNT * BLOCK_SIZE);
        for (int i = 0; i < THREAD_COUNT; i++) {
            data.put(i * BLOCK_SIZE, (byte) i);
        }
        return ByteBufferBlockDevice.wrap(data, false);
    }

    private interface Action {
        void run(int index) throws IOException;
    }

    private static void run(final Action action) throws Exception {
        final CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        final Thread[] threads = new Thread[THREAD_COUNT];
        final Throwable[] errors = new Throwable[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        action.run(index);
                    } catch (final Throwable e) {
                        errors[index] = e;
                        break;
                    }
                }
            });
        }

        for (final Thread thread : threads) {
            thread.start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }

        for (final Throwable error : errors) {
            if (error != null) {
                fail(error);
            }
        }
    }
}
