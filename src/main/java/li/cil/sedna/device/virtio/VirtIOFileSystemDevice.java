package li.cil.sedna.device.virtio;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.fs.*;

import javax.annotation.Nullable;
import java.io.Closeable;
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
import java.util.Arrays;
import java.util.List;

/**
 * Plan 9 file protocol device.
 * <p>
 * Can be used to provide direct access to a {@link FileSystem} implementation, which could be a directory
 * in the host operating system or a ZIP file, for example.
 * <p>
 * References:
 * <ul>
 *     <li>https://github.com/chaos/diod/blob/master/protocol.md</li>
 *     <li>https://github.com/torvalds/linux/blob/master/include/net/9p/9p.h</li>
 *     <li>http://9p.io/magic/man2html/5</li>
 * </ul>
 */
@SuppressWarnings("PointlessBitwiseExpression")
public final class VirtIOFileSystemDevice extends AbstractVirtIODevice implements Steppable {
    private static final int VIRTIO_9P_MAX_MESSAGE_SIZE = 8 * 1024;
    private static final String VIRTIO_9P_VERSION = "9P2000.L";
    private static final int BYTES_PER_THOUSAND_CYCLES = 32;

    private static final long VIRTIO_9P_F_MOUNT_TAG = 1L << 0; // We have a tag name that can be used to mount us.

    private static final byte P9_MSG_TLERROR = 6; // only used in reply; RLERROR: response for any failed request for 9P2000.L
    private static final byte P9_MSG_TSTATFS = 8; // file system status request
    private static final byte P9_MSG_TLOPEN = 12;
    private static final byte P9_MSG_TLCREATE = 14; // prepare a handle for I/O on an new file for 9P2000.L
    private static final byte P9_MSG_TSYMLINK = 16; // make symlink request
    private static final byte P9_MSG_TMKNOD = 18; // create a special file object request
    private static final byte P9_MSG_TRENAME = 20; // rename request
    private static final byte P9_MSG_TREADLINK = 22;
    private static final byte P9_MSG_TGETATTR = 24;
    private static final byte P9_MSG_TSETATTR = 26;
    private static final byte P9_MSG_TXATTRWALK = 30;
    private static final byte P9_MSG_TXATTRCREATE = 32;
    private static final byte P9_MSG_TREADDIR = 40;
    private static final byte P9_MSG_TFSYNC = 50;
    private static final byte P9_MSG_TLOCK = 52;
    private static final byte P9_MSG_TGETLOCK = 54;
    private static final byte P9_MSG_TLINK = 70;
    private static final byte P9_MSG_TMKDIR = 72; // create a directory request
    private static final byte P9_MSG_TRENAMEAT = 74;
    private static final byte P9_MSG_TUNLINKAT = 76;
    private static final byte P9_MSG_TVERSION = 100; // version handshake request
    private static final byte P9_MSG_TAUTH = 102;    // request to establish authentication channel
    private static final byte P9_MSG_TATTACH = 104;  // establish user access to file service
    private static final byte P9_MSG_RERROR = 106;   // response for any failed request
    private static final byte P9_MSG_TFLUSH = 108;   // request to abort a previous request
    private static final byte P9_MSG_TWALK = 110;    // descend a directory hierarchy
    private static final byte P9_MSG_TOPEN = 112;    // prepare a handle for I/O on an existing file
    private static final byte P9_MSG_TCREATE = 114;  // prepare a handle for I/O on a new file
    private static final byte P9_MSG_TREAD = 116;    // request to transfer data from a file or directory
    private static final byte P9_MSG_TWRITE = 118;   // request to transfer data to a file
    private static final byte P9_MSG_TCLUNK = 120;   // forget about a handle to an entity within the file system
    private static final byte P9_MSG_TREMOVE = 122;  // request to remove an entity from the hierarchy
    private static final byte P9_MSG_TSTAT = 124;    // request file entity attributes
    private static final byte P9_MSG_TWSTAT = 126;   // request to update file entity attributes

    // file modes for getattr.
    private static final int P9_S_IRWXUGO = 0x01FF;
    private static final int P9_S_ISVTX = 0x0200;
    private static final int P9_S_ISGID = 0x0400;
    private static final int P9_S_ISUID = 0x0800;

    private static final int P9_S_IFMT = 0xF000;
    private static final int P9_S_IFIFO = 0x1000;
    private static final int P9_S_IFCHR = 0x2000;
    private static final int P9_S_IFDIR = 0x4000;
    private static final int P9_S_IFBLK = 0x6000;
    private static final int P9_S_IFREG = 0x8000;
    private static final int P9_S_IFLNK = 0xA000;
    private static final int P9_S_IFSOCK = 0xC000;

    // flags for open/create.
    private static final int P9_OPEN_RDONLY = 0x00000000;
    private static final int P9_OPEN_WRONLY = 0x00000001;
    private static final int P9_OPEN_RDWR = 0x00000002;
    private static final int P9_OPEN_NOACCESS = 0x00000003;
    private static final int P9_OPEN_CREAT = 0x00000040;
    private static final int P9_OPEN_EXCL = 0x00000080;
    private static final int P9_OPEN_NOCTTY = 0x00000100;
    private static final int P9_OPEN_TRUNC = 0x00000200;
    private static final int P9_OPEN_APPEND = 0x00000400;
    private static final int P9_OPEN_NONBLOCK = 0x00000800;
    private static final int P9_OPEN_DSYNC = 0x00001000;
    private static final int P9_OPEN_FASYNC = 0x00002000;
    private static final int P9_OPEN_DIRECT = 0x00004000;
    private static final int P9_OPEN_LARGEFILE = 0x00008000;
    private static final int P9_OPEN_DIRECTORY = 0x00010000;
    private static final int P9_OPEN_NOFOLLOW = 0x00020000;
    private static final int P9_OPEN_NOATIME = 0x00040000;
    private static final int P9_OPEN_CLOEXEC = 0x00080000;
    private static final int P9_OPEN_SYNC = 0x00100000;

    // mask bits for getattr/setattr.
    private static final long P9_GETATTR_MODE = 0x00000001L;
    private static final long P9_GETATTR_NLINK = 0x00000002L;
    private static final long P9_GETATTR_UID = 0x00000004L;
    private static final long P9_GETATTR_GID = 0x00000008L;
    private static final long P9_GETATTR_RDEV = 0x00000010L;
    private static final long P9_GETATTR_ATIME = 0x00000020L;
    private static final long P9_GETATTR_MTIME = 0x00000040L;
    private static final long P9_GETATTR_CTIME = 0x00000080L;
    private static final long P9_GETATTR_INO = 0x00000100L;
    private static final long P9_GETATTR_SIZE = 0x00000200L;
    private static final long P9_GETATTR_BLOCKS = 0x00000400L;

    // qid types.
    private static final byte P9_QID_TYPE_DIR = (byte) 0x80;
    private static final byte P9_QID_TYPE_APPEND = 0x40;
    private static final byte P9_QID_TYPE_EXCL = 0x20;
    private static final byte P9_QID_TYPE_MOUNT = 0x10;
    private static final byte P9_QID_TYPE_AUTH = 0x08;
    private static final byte P9_QID_TYPE_TMP = 0x04;
    private static final byte P9_QID_TYPE_SYMLINK = 0x02;
    private static final byte P9_QID_TYPE_LINK = 0x01;
    private static final byte P9_QID_TYPE_FILE = 0x00;

    // readdir d_type.
    private static final byte DT_UNKNOWN = 0;
    private static final byte DT_FIFO = 1;
    private static final byte DT_CHR = 2;
    private static final byte DT_DIR = 4;
    private static final byte DT_BLK = 6;
    private static final byte DT_REG = 8;
    private static final byte DT_LNK = 10;
    private static final byte DT_SOCK = 12;
    private static final byte DT_WHT = 14;

    // https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/errno-base.h
    // https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/errno.h
    private static final int LINUX_ERRNO_EPERM = 1;      // Operation not permitted
    private static final int LINUX_ERRNO_ENOENT = 2;     // No such file or directory
    private static final int LINUX_ERRNO_EIO = 5;        // I/O error
    private static final int LINUX_ERRNO_EEXIST = 17;    // File exists
    private static final int LINUX_ERRNO_ENOTDIR = 20;   // Not a directory
    private static final int LINUX_ERRNO_EINVAL = 22;    // Invalid argument
    private static final int LINUX_ERRNO_ENOSPC = 28;    // No space left on device
    private static final int LINUX_ERRNO_ENOTEMPTY = 39; // Directory not empty
    private static final int LINUX_ERRNO_EPROTO = 71;    // Protocol error
    private static final int LINUX_ERRNO_ENOTSUPP = 524;  // Not supported

    private static final int VIRTQ_REQUEST = 0;

    private final String tag;
    private final FileSystem fileSystem;
    private int remainingByteProcessingQuota;

    @Serialized private final FileSystemFileMap files = new FileSystemFileMap();
    @Serialized private boolean hasPendingRequest;

    public VirtIOFileSystemDevice(final MemoryMap memoryMap, final String tag, final FileSystem fileSystem) {
        super(memoryMap, VirtIODeviceSpec
                .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_9P_TRANSPORT)
                .features(VIRTIO_9P_F_MOUNT_TAG)
                .queueCount(1)
                .configSpaceSize(2 + Math.min(tag.length(), 0xFFFF))
                .build());
        this.tag = tag;
        this.fileSystem = fileSystem;
    }

    @Override
    public void reset() {
        super.reset();

        closeFilesAndClearFIDs();
    }

    @Override
    public void step(final int cycles) {
        if (remainingByteProcessingQuota <= 0) {
            remainingByteProcessingQuota += Math.max(1, cycles * BYTES_PER_THOUSAND_CYCLES / 1000);
        }

        if (!hasPendingRequest) {
            return;
        }

        if ((getStatus() & VIRTIO_STATUS_FAILED) != 0) {
            return;
        }

        try {
            while (remainingByteProcessingQuota > 0) {
                final int processedBytes = processRequest();
                if (processedBytes < 0) {
                    break;
                }
                remainingByteProcessingQuota -= processedBytes;
            }
        } catch (final Throwable e) {
            error();
        }
    }

    @Override
    protected void initializeConfig() {
        super.initializeConfig();
        final ByteBuffer configuration = getConfiguration();
        configuration.clear();
        configuration.putShort((short) tag.length());
        configuration.put(tag.getBytes(StandardCharsets.US_ASCII));
        configuration.flip();
    }

    @Override
    protected void handleQueueNotification(final int queueIndex) {
        hasPendingRequest = true;
    }

    private int processRequest() throws VirtIODeviceException, IOException {
        final VirtqueueIterator queue = getQueueIterator(VIRTQ_REQUEST);
        if (queue == null) {
            hasPendingRequest = false;
            return -1;
        }

        if (!queue.hasNext()) {
            hasPendingRequest = false;
            return -1;
        }
        final DescriptorChain chain = queue.next();

        final int processedBytes = chain.readableBytes() + chain.writableBytes();

        // struct p9_fcall {
        //     u32 size;
        //     u8 id;
        //     u16 tag;
        //
        //     size_t offset;
        //     size_t capacity;
        //
        //     struct kmem_cache *cache;
        //     u8 *sdata;
        // };

        final ByteBuffer request = ByteBuffer.allocate(chain.readableBytes()).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer reply = ByteBuffer.allocate(VIRTIO_9P_MAX_MESSAGE_SIZE - chain.readableBytes()).order(ByteOrder.LITTLE_ENDIAN);

        chain.get(request);
        request.flip();

        request.getInt(); // size, unused
        final byte id = request.get();
        final short tag = request.getShort();

        try {
            switch (id) {
                case P9_MSG_TVERSION: {
                    version(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TFLUSH: {
                    flush(chain, id, tag);
                    break;
                }

                case P9_MSG_TWALK: {
                    walk(chain, request, reply, id, tag);
                    break;
                }

                case P9_MSG_TREAD: {
                    read(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TWRITE: {
                    write(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TCLUNK: {
                    clunk(chain, request, id, tag);
                    break;
                }

                // P9_MSG_TREMOVE

                case P9_MSG_TATTACH: {
                    attach(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TSTATFS: {
                    statfs(chain, reply, id, tag);
                    break;
                }

                case P9_MSG_TLOPEN: {
                    open(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TLCREATE: {
                    create(chain, request, id, tag, reply);
                    break;
                }

                // P9_MSG_TSYMLINK
                // P9_MSG_TMKNOD
                // P9_MSG_TRENAME
                // P9_MSG_TREADLINK

                case P9_MSG_TGETATTR: {
                    getattr(chain, request, id, tag, reply);
                    break;
                }

                // P9_MSG_TSETATTR
                // P9_MSG_TXATTRWALK

                case P9_MSG_TREADDIR: {
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

                        final byte d_type;
                        switch (entry.type) {
                            case FILE:
                                d_type = DT_REG;
                                break;
                            case DIRECTORY:
                                d_type = DT_DIR;
                                break;
                            default:
                                d_type = DT_UNKNOWN;
                                break;
                        }

                        // qid[13] offset[8] type[1] name[s]
                        putQID(reply, getQID(path.resolve(entry.name)));
                        reply.putLong(i + 1);
                        reply.put(d_type);
                        putString(reply, entry.name);
                    }
                    reply.putInt(0, reply.position() - dataStart);

                    putReply(chain, id, tag, reply);
                    break;
                }

                case P9_MSG_TFSYNC: {
                    fsync(chain, request, id, tag);
                    break;
                }

                // P9_MSG_TLOCK
                // P9_MSG_TGETLOCK
                // P9_MSG_TLINK

                case P9_MSG_TMKDIR: {
                    mkdir(chain, request, id, tag, reply);
                    break;
                }

                case P9_MSG_TRENAMEAT: {
                    renameat(chain, request, id, tag);
                    break;
                }

                case P9_MSG_TUNLINKAT: {
                    unlinkat(chain, request, id, tag);
                    break;
                }

                default: {
                    throw new UnsupportedOperationException();
                }
            }
        } catch (final MemoryAccessException e) {
            throw e;
        } catch (final SecurityException e) {
            lerror(chain, tag, LINUX_ERRNO_EPERM);
        } catch (final NoSuchFileException e) {
            lerror(chain, tag, LINUX_ERRNO_ENOENT);
        } catch (final FileAlreadyExistsException e) {
            lerror(chain, tag, LINUX_ERRNO_EEXIST);
        } catch (final NotDirectoryException e) {
            lerror(chain, tag, LINUX_ERRNO_ENOTDIR);
        } catch (final DirectoryNotEmptyException e) {
            lerror(chain, tag, LINUX_ERRNO_ENOTEMPTY);
        } catch (final IOException e) {
            lerror(chain, tag, LINUX_ERRNO_EIO);
        } catch (final UnsupportedOperationException e) {
            lerror(chain, tag, LINUX_ERRNO_ENOTSUPP);
        }

        chain.use();

        return processedBytes;
    }

    private void version(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
        // size[4] Tversion tag[2] msize[4] version[s]
        // size[4] Rversion tag[2] msize[4] version[s]
        final int msize = request.getInt();
        // offered version is ignored. We always reply with ours.

        // version(5): The server responds with its own maximum, msize, which must be less than or equal to the client's value.
        reply.putInt(Math.min(msize, VIRTIO_9P_MAX_MESSAGE_SIZE));
        putString(reply, VIRTIO_9P_VERSION);

        // version(5): A successful version request initializes the connection. All outstanding I/O on the connection
        // is aborted; all active fids are freed (`clunked') automatically.
        closeFilesAndClearFIDs();

        putReply(chain, id, tag, reply);
    }

    private void flush(final DescriptorChain chain, final byte id, final short tag) throws MemoryAccessException, VirtIODeviceException {
        // size[4] Tflush tag[2] oldtag[2]
        // size[4] Rflush tag[2]

        // No-op.
        putReply(chain, id, tag);
    }

    private void walk(final DescriptorChain chain, final ByteBuffer request, final ByteBuffer reply, final byte id, final short tag) throws VirtIODeviceException, IOException {
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

        putReply(chain, id, tag, reply);
    }

    private void read(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
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

        putReply(chain, id, tag, reply);
    }

    private void write(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
        // size[4] Twrite tag[2] fid[4] offset[8] count[4] data[count]
        // size[4] Rwrite tag[2] count[4]
        final int fid = request.getInt();
        final long offset = request.getLong();
        int count = request.getInt();

        final FileSystemFile file = getFile(fid);

        request.limit(request.position() + count);
        count = file.write(fileSystem, offset, request);
        reply.putInt(count);

        putReply(chain, id, tag, reply);
    }

    private void clunk(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag) throws IOException, VirtIODeviceException {
        // size[4] Tclunk tag[2] fid[4]
        // size[4] Rclunk tag[2]
        final int fid = request.getInt();

        clunk(fid);

        putReply(chain, id, tag);
    }

    private void attach(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
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
        putReply(chain, id, tag, reply);
    }

    private void statfs(final DescriptorChain chain, final ByteBuffer reply, final byte id, final short tag) throws IOException, VirtIODeviceException {
        // size[4] Tstatfs tag[2] fid[4]
        // size[4] Rstatfs tag[2] type[4] bsize[4] blocks[8] bfree[8] bavail[8]
        //                        files[8] ffree[8] fsid[8] namelen[4]

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

        putReply(chain, id, tag, reply);
    }

    private void open(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
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
        final int readWriteSumRequestResponseHeaderSize = 34;
        reply.putInt(VIRTIO_9P_MAX_MESSAGE_SIZE - readWriteSumRequestResponseHeaderSize);
        putReply(chain, id, tag, reply);
    }

    private void create(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
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
        final int readWriteSumRequestResponseHeaderSize = 34;
        reply.putInt(VIRTIO_9P_MAX_MESSAGE_SIZE - readWriteSumRequestResponseHeaderSize);
        putReply(chain, id, tag, reply);
    }

    private void getattr(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
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

        long replyMask = request_mask & (P9_GETATTR_MODE | P9_GETATTR_SIZE);
        final FileTime lastAccessTime = attributes.lastAccessTime();
        if (lastAccessTime != null) {
            replyMask |= P9_GETATTR_ATIME;
        }
        final FileTime lastModifiedTime = attributes.lastModifiedTime();
        if (lastModifiedTime != null) {
            replyMask |= P9_GETATTR_MTIME;
        }
        final FileTime creationTime = attributes.creationTime();
        if (creationTime != null) {
            replyMask |= P9_GETATTR_CTIME;
        }

        reply.putLong(replyMask);
        putQID(reply, getQID(file));
        int mode = fileSystem.isDirectory(path) ? P9_S_IFDIR : P9_S_IFREG;
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

        putReply(chain, id, tag, reply);
    }

    private void fsync(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag) throws IOException, VirtIODeviceException {
        // size[4] Tfsync tag[2] fid[4]
        // size[4] Rfsync tag[2]
        final int fid = request.getInt();

        getFile(fid); // Validate, no-op other than that.

        putReply(chain, id, tag);
    }

    private void mkdir(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag, final ByteBuffer reply) throws IOException, VirtIODeviceException {
        // size[4] Tmkdir tag[2] dfid[4] name[s] mode[4] gid[4]
        // size[4] Rmkdir tag[2] qid[13]
        final int dfid = request.getInt();
        final String name = getString(request);
        request.getInt(); // mode, unused.
        request.getInt(); // gid, unused.

        final FileSystemFile dir = getFile(dfid);
        final Path path = dir.getPath().resolve(name);
        fileSystem.mkdir(path);

        final QID qid = getQID(path);

        putQID(reply, qid);
        putReply(chain, id, tag, reply);
    }

    private void renameat(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag) throws IOException, VirtIODeviceException {
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

        putReply(chain, id, tag);
    }

    private void unlinkat(final DescriptorChain chain, final ByteBuffer request, final byte id, final short tag) throws IOException, VirtIODeviceException {
        // size[4] Tunlinkat tag[2] dirfd[4] name[s] flags[4]
        // size[4] Runlinkat tag[2]
        final int dirfd = request.getInt();
        final String name = getString(request);
        request.getInt(); // flags, unused.

        final FileSystemFile dir = getFile(dirfd);
        final Path path = dir.getPath().resolve(name);
        fileSystem.unlink(path);

        putReply(chain, id, tag);
    }

    private static int convertFlags(final int flags) {
        int result = 0;
        if ((flags & P9_OPEN_WRONLY) != 0) {
            result |= FileMode.WRITE;
        }
        if ((flags & P9_OPEN_RDWR) != 0) {
            result |= (FileMode.READ | FileMode.WRITE);
        }

        if (result == 0) {
            result = FileMode.READ;
        }

        if ((flags & P9_OPEN_TRUNC) != 0 && (result & FileMode.WRITE) != 0) {
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
            qid.type = P9_QID_TYPE_DIR;
        } else {
            qid.type = P9_QID_TYPE_FILE;
        }
        qid.version = 0;
        qid.path = fileSystem.getUniqueId(path);
        return qid;
    }

    private String getString(final ByteBuffer buffer) {
        final int strlen = buffer.getShort() & 0xFFFF;
        final byte[] bytes = new byte[strlen];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private void putString(final ByteBuffer buffer, final String value) throws IOException {
        if (value.length() > 0xFFFF) throw new IOException();
        buffer.putShort((short) value.length());
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void putQID(final ByteBuffer buffer, final QID qid) {
        buffer.put(qid.type);
        buffer.putInt(qid.version);
        buffer.putLong(qid.path);
    }

    private void lerror(final DescriptorChain chain, final short tag, final int error) throws MemoryAccessException, VirtIODeviceException {
        putReply(chain, P9_MSG_TLERROR, tag, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(error));
    }

    private void putReply(final DescriptorChain chain, final byte messageId, final short tag) throws MemoryAccessException, VirtIODeviceException {
        putReply(chain, messageId, tag, null);
    }

    private void putReply(final DescriptorChain chain, final byte messageId, final short tag, @Nullable final ByteBuffer data) throws MemoryAccessException, VirtIODeviceException {
        if (data != null) data.flip();
        final int dataLength = data != null ? data.remaining() : 0;
        final ByteBuffer message = ByteBuffer.allocate(4 + 1 + 2 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(message.remaining());
        message.put((byte) (messageId + 1)); // Reply message type is always message type + 1.
        message.putShort(tag);
        if (data != null) message.put(data);
        message.flip();
        chain.skip(chain.readableBytes());
        chain.put(message);
    }

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

    public static final class QID {
        // type[1] version[4] path[8]
        public byte type;
        public int version;
        public long path;
    }

    // Explicit non-generic type for serialization.
    public static final class FileSystemFileMap extends Int2ObjectArrayMap<FileSystemFile> {
    }

    /**
     * A reference to some object within a {@link FileSystem}.
     * <p>
     * This class represents all data associated with a fid.
     * <p>
     * This can reference either a file or a directory; we call it "File" because that's how the specification
     * refers to it (e.g. from clunk(5): The clunk request informs the file server that the current file represented by
     * fid is no longer needed by the client).
     */
    public static final class FileSystemFile implements Closeable {
        @Serialized public int id;
        @Serialized public String[] pathParts;
        @Serialized public boolean isOpen;
        @Serialized public int openFlags;

        private Path path;
        private FileHandle handle;

        // For deserialization.
        public FileSystemFile() {
        }

        public FileSystemFile(final int id, final Path path) {
            this.id = id;
            this.path = path;
            this.pathParts = path.getParts();
        }

        @Override
        public void close() {
            if (handle != null) {
                try {
                    handle.close();
                } catch (final IOException ignored) {
                }
            }
            handle = null;
            isOpen = false;
            openFlags = FileMode.NONE;
        }

        public FileHandle getHandle(final FileSystem fileSystem) throws IOException {
            if (isOpen && handle == null) {
                handle = fileSystem.open(getPath(), openFlags);
            }
            if (handle == null) {
                throw new IOException();
            }
            return handle;
        }

        public void setHandle(final FileHandle handle, final int flags) {
            close();
            isOpen = true;
            openFlags = flags & ~FileMode.TRUNCATE; // Don't truncate when re-opening after deserialization.
            this.handle = handle;
        }

        public boolean isOpen() {
            return isOpen;
        }

        public Path getPath() {
            if (path == null) {
                path = new Path(Arrays.asList(pathParts));
            }
            return path;
        }

        public void setPath(final Path path) {
            close();
            this.path = path;
            this.pathParts = path.getParts();
        }

        public int read(final FileSystem fileSystem, final long offset, final ByteBuffer buffer) throws IOException {
            return getHandle(fileSystem).read(offset, buffer);
        }

        public int write(final FileSystem fileSystem, final long offset, final ByteBuffer buffer) throws IOException {
            return getHandle(fileSystem).write(offset, buffer);
        }

        public List<DirectoryEntry> readdir(final FileSystem fileSystem) throws IOException {
            return getHandle(fileSystem).readdir();
        }
    }
}
