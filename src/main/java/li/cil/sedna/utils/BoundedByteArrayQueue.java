package li.cil.sedna.utils;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedByteArrayQueue {
    private final int capacity;
    private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    public BoundedByteArrayQueue(final int capacity) {
        this.capacity = capacity;
    }

    public boolean offer(final byte[] value) {
        if (size.addAndGet(value.length) > capacity) {
            size.addAndGet(-value.length);
            return false;
        }
        queue.offer(value);
        return true;
    }

    @Nullable
    public byte[] poll() {
        final byte[] value = queue.poll();
        if (value != null) {
            size.addAndGet(-value.length);
        }
        return value;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int byteSize() {
        return size.get();
    }

    public int capacity() {
        return capacity;
    }

    public byte[][] toArray() {
        return queue.toArray(new byte[0][]);
    }

    public void clear() {
        while (poll() != null) {
        }
    }
}
