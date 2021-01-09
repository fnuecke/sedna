package li.cil.sedna.serialization.serializers;

import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.device.block.SparseBlockDevice.SparseBlockMap;

import javax.annotation.Nullable;

public final class SparseBlockMapSerializer implements Serializer<SparseBlockMap> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<SparseBlockMap> type, final Object value) throws SerializationException {
        final SparseBlockMap map = (SparseBlockMap) value;

        final int[] keys = map.keySet().toArray(new int[0]);
        final byte[][] values = new byte[map.size()][];
        for (int i = 0; i < keys.length; i++) {
            values[i] = map.get(keys[i]);
        }

        visitor.putObject("keys", int[].class, keys);
        visitor.putObject("values", byte[][].class, values);
    }

    @Override
    public SparseBlockMap deserialize(final DeserializationVisitor visitor, final Class<SparseBlockMap> type, @Nullable final Object value) throws SerializationException {
        SparseBlockMap map = (SparseBlockMap) value;
        if (!visitor.exists("keys") || !visitor.exists("values")) {
            return map;
        }

        final int[] keys = (int[]) visitor.getObject("keys", int[].class, null);
        final byte[][] values = (byte[][]) visitor.getObject("values", byte[][].class, null);
        if (keys == null || values == null) {
            return null;
        }

        if (map == null) {
            map = new SparseBlockMap(keys.length);
        } else {
            map.clear();

            for (int i = 0; i < keys.length; i++) {
                map.put(keys[i], values[i]);
            }
        }

        return map;
    }
}
