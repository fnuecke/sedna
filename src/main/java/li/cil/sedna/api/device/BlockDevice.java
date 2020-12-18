package li.cil.sedna.api.device;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface BlockDevice extends Closeable {
    /**
     * Returns whether this device is read-only.
     * <p>
     * When {@code true}, {@link #getOutputStream()} will throw an {@link UnsupportedOperationException}.
     *
     * @return whether this device is read-only.
     */
    boolean isReadonly();

    /**
     * The overall capacity of this block device in bytes.
     *
     * @return the capacity of the block device.
     */
    long getCapacity();

    /**
     * Get a stream that can be used for reading data from the block device.
     *
     * @param offset the position in the block device to start reading from.
     * @return a stream for reading from the device.
     */
    InputStream getInputStream(long offset);

    /**
     * Get a stream that can be used for reading data from the block device.
     * <p>
     * Starts reading at the start of the block device.
     *
     * @return a stream for reading from the device.
     */
    default InputStream getInputStream() {
        return getInputStream(0);
    }

    /**
     * Get a stream that can be used to write data to the block device.
     *
     * @param offset the position in the block device to start writing at.
     * @return a stream for writing to the device.
     */
    OutputStream getOutputStream(long offset);

    /**
     * Get a stream that can be used to write data to the block device.
     * <p>
     * Starts writing at the start of the block device.
     *
     * @return a stream for writing to the device.
     */
    default OutputStream getOutputStream() {
        return getOutputStream(0);
    }

    default void flush() {
    }

    @Override
    default void close() throws IOException {
    }
}
