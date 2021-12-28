package li.cil.sedna.device.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public final class FileMappedMemory extends ByteBufferMemory {
    private final FileChannel channel;
    private int size;

    public FileMappedMemory(final int size, final File file) throws IOException {
        this(size, new RandomAccessFile(file, "rw"));
    }

    private FileMappedMemory(final int size, final RandomAccessFile file) throws IOException {
        this(size, file.getChannel());
    }

    private FileMappedMemory(final int size, final FileChannel channel) throws IOException {
        super(size, channel.map(FileChannel.MapMode.READ_WRITE, 0, size));
        this.channel = channel;
    }

    @Override
    public void close() throws Exception {
        try {
            channel.close();
        } catch (final IOException ignored) {
        }
        size = 0;
        super.close();
    }

    @Override
    public int getLength() {
        return size;
    }
}
