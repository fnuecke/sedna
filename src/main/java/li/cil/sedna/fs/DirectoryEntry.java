package li.cil.sedna.fs;

import java.io.File;

public final class DirectoryEntry {
    public FileType type;
    public String name;

    public static DirectoryEntry create(final File f) {
        final DirectoryEntry entry = new DirectoryEntry();
        entry.type = f.isDirectory() ? FileType.DIRECTORY : FileType.FILE;
        entry.name = f.getName();
        return entry;
    }
}
