package li.cil.sedna.device.block;

import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.utils.ByteBufferInputStream;
import li.cil.sedna.utils.ByteBufferOutputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class ByteBufferBlockDevice implements BlockDevice {
    private final int capacity;
    private final ByteBuffer data;
    private final boolean readonly;

    public static ByteBufferBlockDevice create(final int size, final boolean readonly) {
        return new ByteBufferBlockDevice(ByteBuffer.allocate(size), readonly);
    }

    public static ByteBufferBlockDevice createFromStream(final InputStream stream, final boolean readonly) throws IOException {
        return new ByteBufferBlockDevice(ByteBuffer.wrap(IOUtils.toByteArray(stream)), readonly);
    }

    public static ByteBufferBlockDevice createFromFile(final File file, final boolean readonly) throws IOException {
        return createFromFile(file, file.length(), readonly);
    }

    public static ByteBufferBlockDevice createFromFile(final File file, final long length, final boolean readonly) throws IOException {
        return new FileByteBufferBlockDevice(new RandomAccessFile(file, readonly ? "r" : "rw"), length, readonly);
    }

    public ByteBufferBlockDevice(final ByteBuffer data, final boolean readonly) {
        this.capacity = data.remaining();
        this.data = ByteBuffer.allocate(capacity);
        this.data.put(data);
        this.readonly = readonly;
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public long getCapacity() {
        return capacity;
    }

    @Override
    public InputStream getInputStream(final long offset) {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }

        data.position((int) offset);
        return new ByteBufferInputStream(data);
    }

    @Override
    public OutputStream getOutputStream(final long offset) {
        if (isReadonly()) {
            throw new UnsupportedOperationException();
        }

        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }

        data.position((int) offset);
        return new ByteBufferOutputStream(data);
    }

    private static final class FileByteBufferBlockDevice extends ByteBufferBlockDevice {
        private final RandomAccessFile file;

        public FileByteBufferBlockDevice(final RandomAccessFile file, final long length, final boolean readonly) throws IOException {
            super(file.getChannel().map(readonly ? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE, 0, length), readonly);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            super.close();
            file.close();
        }
    }
}
