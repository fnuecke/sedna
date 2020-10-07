package li.cil.sedna.fs;

public class FileSystemStats {
    public int blockSize = 512;
    public long blockCount;
    public long freeBlockCount;
    public long availableBlockCount;
    public long fileCount;
    public long freeFileCount;
    public int maxNameLength = 256;
}
