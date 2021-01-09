package li.cil.sedna.serialization.serializers;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice.FileSystemFile;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice.FileSystemFileMap;

import javax.annotation.Nullable;

public final class FileSystemFileMapSerializer implements Serializer<FileSystemFileMap> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<FileSystemFileMap> type, final Object value) throws SerializationException {
        final Int2ObjectArrayMap<?> map = (Int2ObjectArrayMap<?>) value;
        visitor.putObject("keys", int[].class, map.keySet().toIntArray());
        visitor.putObject("values", FileSystemFile[].class, map.values().toArray(new Object[0]));
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
