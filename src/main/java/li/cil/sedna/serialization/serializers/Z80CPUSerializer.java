package li.cil.sedna.serialization.serializers;

import li.cil.ceres.Ceres;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.z80.Z80CPU;
import li.cil.sedna.z80.Z80CPUBase;

import javax.annotation.Nullable;

public final class Z80CPUSerializer implements Serializer<Z80CPU> {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<Z80CPU> type, final Object value) throws SerializationException {
        // Base class explicitly, it holds all the state. Generated implementation is logic only.
        final Serializer<?> serializer = Ceres.getSerializer(Z80CPUBase.class);
        serializer.serialize(visitor, (Class) Z80CPUBase.class, value);
    }

    @Override
    public Z80CPU deserialize(final DeserializationVisitor visitor, final Class<Z80CPU> type, @Nullable final Object value) throws SerializationException {
        final Serializer<Z80CPUBase> serializer = Ceres.getSerializer(Z80CPUBase.class);
        final Z80CPU cpu = serializer.deserialize(visitor, Z80CPUBase.class, (Z80CPUBase) value);

        // Required for in-place deserialization, otherwise caches are based on no longer valid state.
        cpu.invalidateCaches();

        return cpu;
    }
}
