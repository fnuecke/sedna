package li.cil.sedna.p9;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.fs.DirectoryEntry;
import li.cil.sedna.fs.FileHandle;
import li.cil.sedna.fs.FileMode;
import li.cil.sedna.fs.FileSystem;
import li.cil.sedna.fs.FileSystemStats;
import li.cil.sedna.fs.Path;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * A 9P2000.L server on top of a {@link FileSystem}.
 * <p>
 * Transport-agnostic: it consumes a request message and produces the reply message, both as plain
 * buffers. Whatever moves those bytes -- a virtqueue, a socket, a test -- is not this class's
 * concern.
 */
public final class P9Server {
    private final FileSystem fileSystem;
    private final FileSystemFileMap files;

    public P9Server(final FileSystem fileSystem, final FileSystemFileMap files) {
        this.fileSystem = fileSystem;
        this.files = files;
    }

    /**
     * Handles a single request and returns the reply to send back.
     *
     * @param request the complete request message, positioned at its start.
     * @return the complete reply message, ready to be written out.
     * @throws MemoryAccessException if the underlying file system fails to access guest memory.
     *                               Deliberately not mapped to an errno: it means the machine is
     *                               broken, not that the request was bad. It is a subclass of
     *                               {@link IOException}, so this must be caught before that.
     */
    public ByteBuffer handleRequest(final ByteBuffer request) throws MemoryAccessException {
        // version(5): the server responds with a message no larger than the negotiated maximum, and
        // the request is already using part of that budget.
        final ByteBuffer reply = ByteBuffer
            .allocate(P9.MAX_MESSAGE_SIZE - request.remaining())
            .order(ByteOrder.LITTLE_ENDIAN);

        // struct p9_fcall { u32 size; u8 id; u16 tag; ... };
        request.getInt(); // size, unused
        final byte id = request.get();
        final short tag = request.getShort();

        try {
            switch (id) {
                case P9.MSG_TVERSION -> version(request, reply);
                case P9.MSG_TFLUSH -> flush();
                case P9.MSG_TWALK -> walk(request, reply);
                case P9.MSG_TREAD -> read(request, reply);
                case P9.MSG_TWRITE -> write(request, reply);
                case P9.MSG_TCLUNK -> clunk(request);

                // P9.MSG_TREMOVE

                case P9.MSG_TATTACH -> attach(request, reply);
                case P9.MSG_TSTATFS -> statfs(request, reply);
                case P9.MSG_TLOPEN -> open(request, reply);
                case P9.MSG_TLCREATE -> create(request, reply);

                // P9.MSG_TSYMLINK
                // P9.MSG_TMKNOD
                // P9.MSG_TRENAME
                // P9.MSG_TREADLINK

                case P9.MSG_TGETATTR -> getattr(request, reply);

                // P9.MSG_TSETATTR
                // P9.MSG_TXATTRWALK

                case P9.MSG_TREADDIR -> readdir(request, reply);
                case P9.MSG_TFSYNC -> fsync(request);

                // P9.MSG_TLOCK
                // P9.MSG_TGETLOCK
                // P9.MSG_TLINK

                case P9.MSG_TMKDIR -> mkdir(request, reply);
                case P9.MSG_TRENAMEAT -> renameat(request);
                case P9.MSG_TUNLINKAT -> unlinkat(request);
                default -> throw new UnsupportedOperationException();
            }
        } catch (final MemoryAccessException e) {
            throw e;
        } catch (final SecurityException e) {
            return lerror(tag, P9.ERRNO_EPERM);
        } catch (final IllegalArgumentException e) {
            return lerror(tag, P9.ERRNO_EINVAL);
        } catch (final NoSuchFileException e) {
            return lerror(tag, P9.ERRNO_ENOENT);
        } catch (final FileAlreadyExistsException e) {
            return lerror(tag, P9.ERRNO_EEXIST);
        } catch (final NotDirectoryException e) {
            return lerror(tag, P9.ERRNO_ENOTDIR);
        } catch (final DirectoryNotEmptyException e) {
            return lerror(tag, P9.ERRNO_ENOTEMPTY);
        } catch (final IOException e) {
            return lerror(tag, P9.ERRNO_EIO);
        } catch (final UnsupportedOperationException e) {
            return lerror(tag, P9.ERRNO_ENOTSUPP);
        }

        return message(id, tag, reply);
    }

    /**
     * Closes all open files and forgets all fids.
     */
    public void reset() {
        closeFilesAndClearFIDs();
    }

    ///////////////////////////////////////////////////////////////////
    // Message handlers

    private void version(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tversion tag[2] msize[4] version[s]
        // size[4] Rversion tag[2] msize[4] version[s]
        final int msize = request.getInt();
        // offered version is ignored. We always reply with ours.

        // version(5): The server responds with its own maximum, msize, which must be less than or equal to the client's value.
        reply.putInt(Math.min(msize, P9.MAX_MESSAGE_SIZE));
        putString(reply, P9.VERSION);

        // version(5): A successful version request initializes the connection. All outstanding I/O on the connection
        // is aborted; all active fids are freed (`clunked') automatically.
        closeFilesAndClearFIDs();
    }

    private void flush() {
        // size[4] Tflush tag[2] oldtag[2]
        // size[4] Rflush tag[2]

        // No-op.
    }

    private void walk(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Twalk tag[2] fid[4] newfid[4] nwname[2] nwname*(wname[s])
        // size[4] Rwalk tag[2] nwqid[2] nwqid*(wqid[13])

        final int fid = request.getInt();
        final int newfid = request.getInt();
        final int nwname = request.getShort() & 0xFFFF;

        // walk(5): The fid must be valid [...] and must not have been opened for I/O by an open or create message.
        final FileSystemFile file = getFile(fid);
        if (file.isOpen()) {
            throw new IOException();
        }
        // walk(5): if newfid is in use or otherwise illegal, an Rerror is returned.
        if (files.containsKey(newfid)) {
            throw new IOException();
        }

        final QID[] qids = new QID[nwname];

        // walk(5): the walk will return an Rwalk message containing nwqid qids corresponding, in order, to the files
        // that are visited by the nwqid successful elementwise walks; nwqid is therefore either nwname or the index
        // of the first elementwise walk that failed.
        Path path = file.getPath();
        final byte[] wname = new byte[256]; // We don't support names longer than 256 chars.
        int i = 0;
        for (; i < nwname; i++) {
            if (!fileSystem.isDirectory(path)) {
                // walk(5): If the first element cannot be walked for any reason, Rerror is returned.
                if (i == 0) {
                    throw new IOException();
                }
                break;
            }

            final int strlen = request.getShort() & 0xFFFF;
            if (strlen > wname.length) {
                throw new IOException();
            }

            request.get(wname, 0, strlen);
            path = path.resolve(new String(wname, 0, strlen, StandardCharsets.US_ASCII));
            if (!fileSystem.exists(path)) {
                break;
            }

            qids[i] = getQID(path);
        }

        // walk(5): If the full sequence of nwname elements is walked successfully,
        //          newfid will represent the file that results.
        if (i == nwname) {
            establishFID(newfid, path);
        }

        reply.putShort((short) i);
        for (int j = 0; j < i; j++) {
            putQID(reply, qids[j]);
        }
    }

    private void read(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tread tag[2] fid[4] offset[8] count[4]
        // size[4] Rread tag[2] count[4] data[count]
        final int fid = request.getInt();
        final long offset = request.getLong();
        int count = request.getInt();

        final FileSystemFile file = getFile(fid);

        reply.putInt(0); // reserve, will be replaced below
        reply.limit(reply.position() + count);
        count = file.read(fileSystem, offset, reply);
        reply.putInt(0, count);
    }

    private void write(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Twrite tag[2] fid[4] offset[8] count[4] data[count]
        // size[4] Rwrite tag[2] count[4]
        final int fid = request.getInt();
        final long offset = request.getLong();
        int count = request.getInt();

        final FileSystemFile file = getFile(fid);

        request.limit(request.position() + count);
        count = file.write(fileSystem, offset, request);
        reply.putInt(count);
    }

    private void clunk(final ByteBuffer request) {
        // size[4] Tclunk tag[2] fid[4]
        // size[4] Rclunk tag[2]
        final int fid = request.getInt();

        clunk(fid);
    }

    private void attach(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tattach tag[2] fid[4] afid[4] uname[s] aname[s] n_uname[4]
        // size[4] Rattach tag[2] qid[13]
        final int fid = request.getInt();
        request.getInt(); // afid, ignored.
        getString(request); // uname, ignored.
        getString(request); // aname, ignored.
        request.getInt(); // n_uname, ignored.

        // We don't do UIDs and all that. Just create the fid for the root of the file system and return QID for root.

        final FileSystemFile file = establishFID(fid, fileSystem.getRoot());

        putQID(reply, getQID(file));
    }

    private void statfs(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tstatfs tag[2] fid[4]
        // size[4] Rstatfs tag[2] type[4] bsize[4] blocks[8] bfree[8] bavail[8]
        //                        files[8] ffree[8] fsid[8] namelen[4]
        final int fid = request.getInt();

        // The reply describes the whole file system rather than this fid, but the request is still
        // only meaningful for a fid we handed out.
        getFile(fid);

        final FileSystemStats stats = fileSystem.statfs();
        reply.putInt(0); // type
        reply.putInt(stats.blockSize);
        reply.putLong(stats.blockCount);
        reply.putLong(stats.freeBlockCount);
        reply.putLong(stats.availableBlockCount);
        reply.putLong(stats.fileCount);
        reply.putLong(stats.freeFileCount);
        reply.putLong(0); // fsid
        reply.putInt(stats.maxNameLength);
    }

    private void open(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tlopen tag[2] fid[4] flags[4]
        // size[4] Rlopen tag[2] qid[13] iounit[4]
        final int fid = request.getInt();
        final int flags = request.getInt();

        final FileSystemFile file = getFile(fid);
        file.close();

        final Path path = file.getPath();
        final int convertedFlags = convertFlags(flags);
        final FileHandle handle = fileSystem.open(path, convertedFlags);
        file.setHandle(handle, convertedFlags);

        putQID(reply, getQID(file));
        reply.putInt(P9.MAX_MESSAGE_SIZE - READ_WRITE_SUM_REQUEST_RESPONSE_HEADER_SIZE);
    }

    private void create(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tlcreate tag[2] fid[4] name[s] flags[4] mode[4] gid[4]
        // size[4] Rlcreate tag[2] qid[13] iounit[4]
        final int fid = request.getInt();
        final String name = getString(request);
        final int flags = request.getInt();
        request.getInt(); // mode, ignored.
        request.getInt(); // gid, ignored.

        final FileSystemFile file = getFile(fid);
        final Path path = file.getPath().resolve(name);
        final int convertedFlags = convertFlags(flags);
        final FileHandle handle = fileSystem.create(path, convertedFlags);

        file.close();
        file.setPath(path);
        file.setHandle(handle, convertedFlags);

        putQID(reply, getQID(file));
        reply.putInt(P9.MAX_MESSAGE_SIZE - READ_WRITE_SUM_REQUEST_RESPONSE_HEADER_SIZE);
    }

    private void getattr(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tgetattr tag[2] fid[4] request_mask[8]
        // size[4] Rgetattr tag[2] valid[8] qid[13] mode[4] uid[4] gid[4] nlink[8]
        //                  rdev[8] size[8] blksize[8] blocks[8]
        //                  atime_sec[8] atime_nsec[8] mtime_sec[8] mtime_nsec[8]
        //                  ctime_sec[8] ctime_nsec[8] btime_sec[8] btime_nsec[8]
        //                  gen[8] data_version[8]
        final int fid = request.getInt();
        final long request_mask = request.getLong();

        final FileSystemFile file = getFile(fid);
        final Path path = file.getPath();
        final BasicFileAttributes attributes = fileSystem.getAttributes(path);

        long replyMask = request_mask & (P9.GETATTR_MODE | P9.GETATTR_SIZE);
        final FileTime lastAccessTime = attributes.lastAccessTime();
        if (lastAccessTime != null) {
            replyMask |= P9.GETATTR_ATIME;
        }
        final FileTime lastModifiedTime = attributes.lastModifiedTime();
        if (lastModifiedTime != null) {
            replyMask |= P9.GETATTR_MTIME;
        }
        final FileTime creationTime = attributes.creationTime();
        if (creationTime != null) {
            replyMask |= P9.GETATTR_CTIME;
        }

        reply.putLong(replyMask);
        putQID(reply, getQID(file));
        int mode = fileSystem.isDirectory(path) ? P9.S_IFDIR : P9.S_IFREG;
        if (fileSystem.isExecutable(path)) {
            mode |= 0111;
        }
        if (fileSystem.isWritable(path)) {
            mode |= 0222;
        }
        if (fileSystem.isReadable(path)) {
            mode |= 0444;
        }
        reply.putInt(mode); // mode, always pretend we have max rights.
        reply.putInt(0); // uid, not supported.
        reply.putInt(0); // gid, not supported.
        reply.putLong(0); // nlink, not supported.
        reply.putLong(0); // rdev, not supported.
        reply.putLong(attributes.size()); // size
        reply.putLong(0); // blksize, not supported.
        reply.putLong(0); // blocks, not supported.
        if (lastAccessTime != null) { // atime_sec
            reply.putLong(lastAccessTime.toInstant().getEpochSecond());
        } else {
            reply.putLong(0);
        }
        reply.putLong(0); // atime_nsec
        if (lastModifiedTime != null) { // mtime_sec
            reply.putLong(lastModifiedTime.toInstant().getEpochSecond());
        } else {
            reply.putLong(0);
        }
        reply.putLong(0); // mtime_nsec
        if (creationTime != null) { // ctime_sec
            reply.putLong(creationTime.toInstant().getEpochSecond());
        } else {
            reply.putLong(0);
        }
        reply.putLong(0); // ctime_nsec
        reply.putLong(0); // btime_sec, reserved.
        reply.putLong(0); // btime_nsec, reserved.
        reply.putLong(0); // gen, reserved.
        reply.putLong(0); // data_version, reserved.
    }

    private void readdir(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Treaddir tag[2] fid[4] offset[8] count[4]
        // size[4] Rreaddir tag[2] count[4] data[count]
        final int fid = request.getInt();
        final long offset = request.getLong();
        final int count = request.getInt();

        final FileSystemFile dir = getFile(fid);
        final Path path = dir.getPath();
        final List<DirectoryEntry> entries = dir.readdir(fileSystem);

        reply.putInt(0); // count, filled in later.
        final int dataStart = reply.position();
        for (int i = (int) offset; i < entries.size(); i++) {
            final DirectoryEntry entry = entries.get(i);
            final int length = 13 // qid[13]
                + 8 // offset[8]
                + 1 // type[1]
                + 2 // nname[2]
                + entry.name.length(); // name[nname]
            if (reply.position() - dataStart + length > count) {
                break;
            }

            final byte d_type = switch (entry.type) {
                case FILE -> P9.DT_REG;
                case DIRECTORY -> P9.DT_DIR;
                default -> P9.DT_UNKNOWN;
            };

            // qid[13] offset[8] type[1] name[s]
            putQID(reply, getQID(path.resolve(entry.name)));
            reply.putLong(i + 1);
            reply.put(d_type);
            putString(reply, entry.name);
        }
        reply.putInt(0, reply.position() - dataStart);
    }

    private void fsync(final ByteBuffer request) throws IOException {
        // size[4] Tfsync tag[2] fid[4]
        // size[4] Rfsync tag[2]
        final int fid = request.getInt();

        getFile(fid); // Validate, no-op other than that.
    }

    private void mkdir(final ByteBuffer request, final ByteBuffer reply) throws IOException {
        // size[4] Tmkdir tag[2] dfid[4] name[s] mode[4] gid[4]
        // size[4] Rmkdir tag[2] qid[13]
        final int dfid = request.getInt();
        final String name = getString(request);
        request.getInt(); // mode, unused.
        request.getInt(); // gid, unused.

        final FileSystemFile dir = getFile(dfid);
        final Path path = dir.getPath().resolve(name);
        fileSystem.mkdir(path);

        putQID(reply, getQID(path));
    }

    private void renameat(final ByteBuffer request) throws IOException {
        // size[4] Trenameat tag[2] olddirfid[4] oldname[s] newdirfid[4] newname[s]
        // size[4] Rrenameat tag[2]
        final int olddirfid = request.getInt();
        final String oldname = getString(request);
        final int newdirfid = request.getInt();
        final String newname = getString(request);

        final FileSystemFile olddir = getFile(olddirfid);
        final FileSystemFile newdir = getFile(newdirfid);
        final Path oldpath = olddir.getPath().resolve(oldname);
        final Path newpath = newdir.getPath().resolve(newname);
        fileSystem.rename(oldpath, newpath);
    }

    private void unlinkat(final ByteBuffer request) throws IOException {
        // size[4] Tunlinkat tag[2] dirfd[4] name[s] flags[4]
        // size[4] Runlinkat tag[2]
        final int dirfd = request.getInt();
        final String name = getString(request);
        request.getInt(); // flags, unused.

        final FileSystemFile dir = getFile(dirfd);
        final Path path = dir.getPath().resolve(name);
        fileSystem.unlink(path);
    }

    ///////////////////////////////////////////////////////////////////
    // Message framing

    private static final int READ_WRITE_SUM_REQUEST_RESPONSE_HEADER_SIZE = 34;

    private static ByteBuffer lerror(final short tag, final int error) {
        return message(P9.MSG_TLERROR, tag,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(error));
    }

    private static ByteBuffer message(final byte messageId, final short tag, final ByteBuffer data) {
        data.flip();
        final int dataLength = data.remaining();
        final ByteBuffer message = ByteBuffer
            .allocate(P9.HEADER_SIZE + dataLength)
            .order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(message.remaining());
        message.put((byte) (messageId + 1)); // Reply message type is always message type + 1.
        message.putShort(tag);
        message.put(data);
        message.flip();
        return message;
    }

    ///////////////////////////////////////////////////////////////////
    // Protocol primitives

    private static int convertFlags(final int flags) {
        int result = 0;
        if ((flags & P9.OPEN_WRONLY) != 0) {
            result |= FileMode.WRITE;
        }
        if ((flags & P9.OPEN_RDWR) != 0) {
            result |= (FileMode.READ | FileMode.WRITE);
        }

        if (result == 0) {
            result = FileMode.READ;
        }

        if ((flags & P9.OPEN_TRUNC) != 0 && (result & FileMode.WRITE) != 0) {
            result |= FileMode.TRUNCATE;
        }

        return result;
    }

    private QID getQID(final FileSystemFile file) throws IOException {
        return getQID(file.getPath());
    }

    private QID getQID(final Path path) throws IOException {
        if (!fileSystem.exists(path)) {
            throw new IOException();
        }
        final QID qid = new QID();
        if (fileSystem.isDirectory(path)) {
            qid.type = P9.QID_TYPE_DIR;
        } else {
            qid.type = P9.QID_TYPE_FILE;
        }
        qid.version = 0;
        qid.path = fileSystem.getUniqueId(path);
        return qid;
    }

    private static String getString(final ByteBuffer buffer) {
        final int strlen = buffer.getShort() & 0xFFFF;
        final byte[] bytes = new byte[strlen];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void putString(final ByteBuffer buffer, final String value) throws IOException {
        if (value.length() > 0xFFFF) throw new IOException();
        buffer.putShort((short) value.length());
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void putQID(final ByteBuffer buffer, final QID qid) {
        buffer.put(qid.type);
        buffer.putInt(qid.version);
        buffer.putLong(qid.path);
    }

    ///////////////////////////////////////////////////////////////////
    // fid table

    private FileSystemFile establishFID(final int fid, final Path path) throws IOException {
        if (files.containsKey(fid)) {
            throw new IOException();
        }

        final FileSystemFile reference = new FileSystemFile(fid, path);
        files.put(fid, reference);
        return reference;
    }

    private FileSystemFile getFile(final int fid) throws IOException {
        if (files.containsKey(fid)) {
            return files.get(fid);
        } else {
            throw new IOException();
        }
    }

    private void clunk(final int fid) {
        final FileSystemFile file = files.remove(fid);
        if (file != null) {
            // Note: not mentioned in the specs that clunked files are closed, but for our
            // purposes (not necessarily trusting the code running in the VM) we definitely
            // want to do this.
            file.close();
        }
    }

    private void closeFilesAndClearFIDs() {
        for (final FileSystemFile file : files.values()) {
            file.close();
        }
        files.clear();
    }
}
