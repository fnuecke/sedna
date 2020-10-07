package li.cil.sedna.fs;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Represents a reference to an <em>opened</em> file or directory in a {@link li.cil.sedna.fs.FileSystem}.
 */
public interface FileHandle extends Closeable {
    /**
     * Reads some data from the file.
     * <p>
     * Starts reading at the specified offset into the file as much data to the specified buffer as possible.
     * <p>
     * <b>Only valid if this handle references a file.</b>
     *
     * @param offset the offset into the file to start reading at.
     * @param buffer the buffer to read data from the file into.
     * @return the number of bytes read.
     */
    int read(final long offset, final ByteBuffer buffer) throws IOException;

    /**
     * Writes some data to the file.
     * <p>
     * Starts writing at the specified offset into the file as much data from the specified buffer as possible.
     * <p>
     * <b>Only valid if this handle references a file.</b>
     *
     * @param offset the offset into the file to start writing at.
     * @param buffer the data to write to the file.
     * @return the number of bytes written.
     */
    int write(final long offset, final ByteBuffer buffer) throws IOException;

    /**
     * Returns a list of all child objects of the directory (subdirectories and files in the directory).
     * <p>
     * <b>Only valid if this handle references a directory.</b>
     *
     * @return the list of entries in this directory.
     */
    List<DirectoryEntry> readdir() throws IOException;
}
