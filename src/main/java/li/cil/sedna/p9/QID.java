package li.cil.sedna.p9;

/**
 * Unique identifier of a file within a file system, as the 9P protocol sees it.
 */
public final class QID {
    // type[1] version[4] path[8]
    public byte type;
    public int version;
    public long path;
}
