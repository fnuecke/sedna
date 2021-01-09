package li.cil.sedna.serialization.serializers;

import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;

import javax.annotation.Nullable;

public final class Int2LongArrayMapSerializer implements Serializer<Int2LongArrayMap> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<Int2LongArrayMap> type, final Object value) throws SerializationException {
        final Int2LongArrayMap map = (Int2LongArrayMap) value;
        visitor.putObject("keys", int[].class, map.keySet().toIntArray());
        visitor.putObject("values", long[].class, map.values().toLongArray());
    }

    @Override
    public Int2LongArrayMap deserialize(final DeserializationVisitor visitor, final Class<Int2LongArrayMap> type, @Nullable final Object value) throws SerializationException {
        Int2LongArrayMap map = (Int2LongArrayMap) value;
        if (visitor.exists("keys") && visitor.exists("values")) {
            if (map == null) {
                map = new Int2LongArrayMap();
            }
            final int[] keys = (int[]) visitor.getObject("keys", int[].class, null);
            final long[] values = (long[]) visitor.getObject("values", long[].class, null);
            if (keys != null && values != null && keys.length == values.length) {
                for (int i = 0; i < keys.length; i++) {
                    map.put(keys[i], values[i]);
                }
            }
        }
        return map;
    }
}
