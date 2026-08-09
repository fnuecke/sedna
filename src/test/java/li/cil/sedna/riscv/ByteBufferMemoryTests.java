package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.device.memory.ByteBufferMemory;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ByteBufferMemoryTests {
    private ByteBufferMemory memory;

    @BeforeEach
    public void initialize() throws Exception {
        memory = new ByteBufferMemory(4 * 1024);
        memory.store(0x00, 0x11223344, Sizes.SIZE_32_LOG2);
        memory.store(0x10, 0x55667788, Sizes.SIZE_32_LOG2);
        memory.store(0x20, 0x99AABBCC, Sizes.SIZE_32_LOG2);
    }

    @Test
    public void testLoad8() throws Exception {
        assertEquals((byte) 0x44, memory.load(0x00 + 0, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x33, memory.load(0x00 + 1, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x22, memory.load(0x00 + 2, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x11, memory.load(0x00 + 3, Sizes.SIZE_8_LOG2));

        assertEquals((byte) 0x88, memory.load(0x10 + 0, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x77, memory.load(0x10 + 1, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x66, memory.load(0x10 + 2, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x55, memory.load(0x10 + 3, Sizes.SIZE_8_LOG2));

        assertEquals((byte) 0xCC, memory.load(0x20 + 0, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0xBB, memory.load(0x20 + 1, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0xAA, memory.load(0x20 + 2, Sizes.SIZE_8_LOG2));
        assertEquals((byte) 0x99, memory.load(0x20 + 3, Sizes.SIZE_8_LOG2));
    }

    @Test
    public void testStore8() throws Exception {
        memory.store(0x00 + 0, (byte) 0x11, Sizes.SIZE_8_LOG2);
        memory.store(0x00 + 1, (byte) 0x22, Sizes.SIZE_8_LOG2);
        memory.store(0x00 + 2, (byte) 0x33, Sizes.SIZE_8_LOG2);
        memory.store(0x00 + 3, (byte) 0x44, Sizes.SIZE_8_LOG2);

        assertEquals(0x44332211, memory.load(0x00 + 0, Sizes.SIZE_32_LOG2));
    }

    @Test
    public void testLoad16() throws Exception {
        assertEquals((short) 0x3344, memory.load(0x00 + 0, Sizes.SIZE_16_LOG2));
        assertEquals((short) 0x1122, memory.load(0x00 + 2, Sizes.SIZE_16_LOG2));

        assertEquals((short) 0x7788, memory.load(0x10 + 0, Sizes.SIZE_16_LOG2));
        assertEquals((short) 0x5566, memory.load(0x10 + 2, Sizes.SIZE_16_LOG2));

        assertEquals((short) 0x99AA, memory.load(0x20 + 2, Sizes.SIZE_16_LOG2));
        assertEquals((short) 0xBBCC, memory.load(0x20 + 0, Sizes.SIZE_16_LOG2));
    }

    @Test
    public void testStore16() throws Exception {
        memory.store(0x00 + 0, (short) 0x2211, Sizes.SIZE_16_LOG2);
        memory.store(0x00 + 2, (short) 0x4433, Sizes.SIZE_16_LOG2);

        assertEquals(0x44332211, memory.load(0x00 + 0, Sizes.SIZE_32_LOG2));
    }

    @Test
    public void testLoad32() throws Exception {
        assertEquals(0x11223344, memory.load(0x00, Sizes.SIZE_32_LOG2));
        assertEquals(0x55667788, memory.load(0x10, Sizes.SIZE_32_LOG2));
        assertEquals(0x99AABBCC, memory.load(0x20, Sizes.SIZE_32_LOG2));
    }

    @Test
    public void testStore32() throws Exception {
        memory.store(0, 0x44332211, Sizes.SIZE_32_LOG2);

        assertEquals(0x44332211, memory.load(0x00, Sizes.SIZE_32_LOG2));
    }

    @Test
    @EnabledIf(value = "storeCasCondition", disabledReason = "Requires multicore system")
    public void testStoreCas() throws Exception {
        final int nThreads = Runtime.getRuntime().availableProcessors();

        final CyclicBarrier barrier = new CyclicBarrier(nThreads);
        final Thread[] threads = new Thread[nThreads];
        final AtomicInteger collisions = new AtomicInteger(0);

        memory.store(0, 0, Sizes.SIZE_64_LOG2);

        for (int i = 0; i < nThreads; i++)
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();

                    for (int j = 0; j < 1_000_000; j++) {
                        boolean first = false;
                        long a;
                        do {
                            if (first)
                                collisions.incrementAndGet();
                            else
                                first = true;

                            a = memory.load(0, Sizes.SIZE_64_LOG2);
                        } while (!memory.storeCAS(0, a + 1, a, Sizes.SIZE_64_LOG2));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

        for (Thread t: threads)
            t.start();

        for (Thread t: threads)
            t.join();

        assertEquals(nThreads * 1_000_000L, memory.load(0, Sizes.SIZE_64_LOG2));
        if (collisions.get() < 0)
            LogManager.getLogger().warn("Test passed but no collisions occurred, results may not be representative.");
    }

    public boolean storeCasCondition() {
        return Runtime.getRuntime().availableProcessors() > 1;
    }
}
