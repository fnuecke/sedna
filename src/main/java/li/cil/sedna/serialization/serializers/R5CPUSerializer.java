package li.cil.sedna.serialization.serializers;

import li.cil.ceres.Ceres;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CPUBase;

import javax.annotation.Nullable;

public final class R5CPUSerializer implements Serializer<R5CPU> {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<R5CPU> type, final Object value) throws SerializationException {
        // Base class explicitly, it holds all the state. Generated implementation is logic only.
        final Serializer<?> serializer = Ceres.getSerializer(R5CPUBase.class);
        serializer.serialize(visitor, (Class) R5CPUBase.class, value);
    }

    @Override
    public R5CPU deserialize(final DeserializationVisitor visitor, final Class<R5CPU> type, @Nullable final Object value) throws SerializationException {
        final Serializer<R5CPUBase> serializer = Ceres.getSerializer(R5CPUBase.class);
        final R5CPU cpu = serializer.deserialize(visitor, R5CPUBase.class, (R5CPUBase) value);

        // Required for in-place deserialization, otherwise caches are based on no longer valid state.
        cpu.invalidateCaches();

        return cpu;
    }
}
