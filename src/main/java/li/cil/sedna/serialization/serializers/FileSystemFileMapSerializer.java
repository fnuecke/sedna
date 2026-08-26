package li.cil.sedna.serialization.serializers;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.p9.FileSystemFile;
import li.cil.sedna.p9.FileSystemFileMap;

import javax.annotation.Nullable;

public final class FileSystemFileMapSerializer implements Serializer<FileSystemFileMap> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<FileSystemFileMap> type, final Object value) throws SerializationException {
        final FileSystemFileMap map = (FileSystemFileMap) value;
        final int[] keys = new int[map.size()];
        final FileSystemFile[] values = new FileSystemFile[map.size()];
        int i = 0;
        for (final Int2ObjectMap.Entry<FileSystemFile> entry : map.int2ObjectEntrySet()) {
            keys[i] = entry.getIntKey();
            values[i] = entry.getValue();
            i++;
        }
        visitor.putObject("keys", int[].class, keys);
        visitor.putObject("values", FileSystemFile[].class, values);
    }

    @Override
    public FileSystemFileMap deserialize(final DeserializationVisitor visitor, final Class<FileSystemFileMap> type, @Nullable final Object value) throws SerializationException {
        FileSystemFileMap map = (FileSystemFileMap) value;
        if (visitor.exists("keys") && visitor.exists("values")) {
            if (map == null) {
                map = new FileSystemFileMap();
            }
            final int[] keys = (int[]) visitor.getObject("keys", int[].class, null);
            final FileSystemFile[] values = (FileSystemFile[]) visitor.getObject("values", FileSystemFile[].class, null);
            if (keys != null && values != null && keys.length == values.length) {
                for (int i = 0; i < keys.length; i++) {
                    map.put(keys[i], values[i]);
                }
            }
        }
        return map;
    }
}
