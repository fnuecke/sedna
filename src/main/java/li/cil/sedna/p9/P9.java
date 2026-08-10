package li.cil.sedna.p9;

/**
 * Constants of the 9P2000.L protocol.
 * <p>
 * References:
 * <ul>
 *     <li>https://github.com/chaos/diod/blob/master/protocol.md</li>
 *     <li>https://github.com/torvalds/linux/blob/master/include/net/9p/9p.h</li>
 *     <li>http://9p.io/magic/man2html/5</li>
 * </ul>
 */
@SuppressWarnings("PointlessBitwiseExpression")
public final class P9 {
    public static final int MAX_MESSAGE_SIZE = 8 * 1024;
    public static final String VERSION = "9P2000.L";

    /**
     * Size of the fixed part of every message: size[4] id[1] tag[2].
     */
    static final int HEADER_SIZE = 4 + 1 + 2;

    static final byte MSG_TLERROR = 6; // only used in reply; RLERROR: response for any failed request for 9P2000.L
    static final byte MSG_TSTATFS = 8; // file system status request
    static final byte MSG_TLOPEN = 12;
    static final byte MSG_TLCREATE = 14; // prepare a handle for I/O on an new file for 9P2000.L
    static final byte MSG_TSYMLINK = 16; // make symlink request
    static final byte MSG_TMKNOD = 18; // create a special file object request
    static final byte MSG_TRENAME = 20; // rename request
    static final byte MSG_TREADLINK = 22;
    static final byte MSG_TGETATTR = 24;
    static final byte MSG_TSETATTR = 26;
    static final byte MSG_TXATTRWALK = 30;
    static final byte MSG_TXATTRCREATE = 32;
    static final byte MSG_TREADDIR = 40;
    static final byte MSG_TFSYNC = 50;
    static final byte MSG_TLOCK = 52;
    static final byte MSG_TGETLOCK = 54;
    static final byte MSG_TLINK = 70;
    static final byte MSG_TMKDIR = 72; // create a directory request
    static final byte MSG_TRENAMEAT = 74;
    static final byte MSG_TUNLINKAT = 76;
    static final byte MSG_TVERSION = 100; // version handshake request
    static final byte MSG_TAUTH = 102;    // request to establish authentication channel
    static final byte MSG_TATTACH = 104;  // establish user access to file service
    static final byte MSG_RERROR = 106;   // response for any failed request
    static final byte MSG_TFLUSH = 108;   // request to abort a previous request
    static final byte MSG_TWALK = 110;    // descend a directory hierarchy
    static final byte MSG_TOPEN = 112;    // prepare a handle for I/O on an existing file
    static final byte MSG_TCREATE = 114;  // prepare a handle for I/O on a new file
    static final byte MSG_TREAD = 116;    // request to transfer data from a file or directory
    static final byte MSG_TWRITE = 118;   // request to transfer data to a file
    static final byte MSG_TCLUNK = 120;   // forget about a handle to an entity within the file system
    static final byte MSG_TREMOVE = 122;  // request to remove an entity from the hierarchy
    static final byte MSG_TSTAT = 124;    // request file entity attributes
    static final byte MSG_TWSTAT = 126;   // request to update file entity attributes

    // file modes for getattr.
    static final int S_IRWXUGO = 0x01FF;
    static final int S_ISVTX = 0x0200;
    static final int S_ISGID = 0x0400;
    static final int S_ISUID = 0x0800;

    static final int S_IFMT = 0xF000;
    static final int S_IFIFO = 0x1000;
    static final int S_IFCHR = 0x2000;
    static final int S_IFDIR = 0x4000;
    static final int S_IFBLK = 0x6000;
    static final int S_IFREG = 0x8000;
    static final int S_IFLNK = 0xA000;
    static final int S_IFSOCK = 0xC000;

    // flags for open/create.
    static final int OPEN_RDONLY = 0x00000000;
    static final int OPEN_WRONLY = 0x00000001;
    static final int OPEN_RDWR = 0x00000002;
    static final int OPEN_NOACCESS = 0x00000003;
    static final int OPEN_CREAT = 0x00000040;
    static final int OPEN_EXCL = 0x00000080;
    static final int OPEN_NOCTTY = 0x00000100;
    static final int OPEN_TRUNC = 0x00000200;
    static final int OPEN_APPEND = 0x00000400;
    static final int OPEN_NONBLOCK = 0x00000800;
    static final int OPEN_DSYNC = 0x00001000;
    static final int OPEN_FASYNC = 0x00002000;
    static final int OPEN_DIRECT = 0x00004000;
    static final int OPEN_LARGEFILE = 0x00008000;
    static final int OPEN_DIRECTORY = 0x00010000;
    static final int OPEN_NOFOLLOW = 0x00020000;
    static final int OPEN_NOATIME = 0x00040000;
    static final int OPEN_CLOEXEC = 0x00080000;
    static final int OPEN_SYNC = 0x00100000;

    // mask bits for getattr/setattr.
    static final long GETATTR_MODE = 0x00000001L;
    static final long GETATTR_NLINK = 0x00000002L;
    static final long GETATTR_UID = 0x00000004L;
    static final long GETATTR_GID = 0x00000008L;
    static final long GETATTR_RDEV = 0x00000010L;
    static final long GETATTR_ATIME = 0x00000020L;
    static final long GETATTR_MTIME = 0x00000040L;
    static final long GETATTR_CTIME = 0x00000080L;
    static final long GETATTR_INO = 0x00000100L;
    static final long GETATTR_SIZE = 0x00000200L;
    static final long GETATTR_BLOCKS = 0x00000400L;

    // qid types.
    static final byte QID_TYPE_DIR = (byte) 0x80;
    static final byte QID_TYPE_APPEND = 0x40;
    static final byte QID_TYPE_EXCL = 0x20;
    static final byte QID_TYPE_MOUNT = 0x10;
    static final byte QID_TYPE_AUTH = 0x08;
    static final byte QID_TYPE_TMP = 0x04;
    static final byte QID_TYPE_SYMLINK = 0x02;
    static final byte QID_TYPE_LINK = 0x01;
    static final byte QID_TYPE_FILE = 0x00;

    // readdir d_type.
    static final byte DT_UNKNOWN = 0;
    static final byte DT_FIFO = 1;
    static final byte DT_CHR = 2;
    static final byte DT_DIR = 4;
    static final byte DT_BLK = 6;
    static final byte DT_REG = 8;
    static final byte DT_LNK = 10;
    static final byte DT_SOCK = 12;
    static final byte DT_WHT = 14;

    // https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/errno-base.h
    // https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/errno.h
    static final int ERRNO_EPERM = 1;      // Operation not permitted
    static final int ERRNO_ENOENT = 2;     // No such file or directory
    static final int ERRNO_EIO = 5;        // I/O error
    static final int ERRNO_EEXIST = 17;    // File exists
    static final int ERRNO_ENOTDIR = 20;   // Not a directory
    static final int ERRNO_EINVAL = 22;    // Invalid argument
    static final int ERRNO_ENOSPC = 28;    // No space left on device
    static final int ERRNO_ENOTEMPTY = 39; // Directory not empty
    static final int ERRNO_EPROTO = 71;    // Protocol error
    static final int ERRNO_ENOTSUPP = 524; // Not supported

    private P9() {
    }
}
