package li.cil.sedna.device.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public final class FileMappedMemory extends ByteBufferMemory {
    private final RandomAccessFile file;
    private int size;

    public FileMappedMemory(final int size, final File file) throws IOException {
        this(size, new RandomAccessFile(file, "rw"));
    }

    private FileMappedMemory(final int size, final RandomAccessFile file) throws IOException {
        super(file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size));
        this.file = file;
        this.size = size;
    }

    @Override
    public void close() throws Exception {
        try {
            file.close();
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
