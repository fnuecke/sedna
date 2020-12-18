package li.cil.sedna.serialization.serializers;

import li.cil.ceres.api.*;
import li.cil.sedna.device.block.SparseBlockDevice;

import javax.annotation.Nullable;

@RegisterSerializer
public final class SparseBlockMapSerializer implements Serializer<SparseBlockDevice.SparseBlockMap> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<SparseBlockDevice.SparseBlockMap> type, final Object value) throws SerializationException {
        final SparseBlockDevice.SparseBlockMap map = (SparseBlockDevice.SparseBlockMap) value;
        final int count = map.size();

        visitor.putInt("count", count);

        final int[] keys = map.keySet().toArray(new int[0]);
        final byte[][] values = new byte[map.size()][];
        for (int i = 0; i < keys.length; i++) {
            values[i] = map.get(keys[i]);
        }

        visitor.putObject("keys", int[].class, keys);
        visitor.putObject("values", byte[][].class, values);
    }

    @Override
    public SparseBlockDevice.SparseBlockMap deserialize(final DeserializationVisitor visitor, final Class<SparseBlockDevice.SparseBlockMap> type, @Nullable final Object value) throws SerializationException {
        return null;
    }
}
