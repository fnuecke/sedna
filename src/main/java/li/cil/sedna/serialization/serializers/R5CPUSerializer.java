package li.cil.sedna.serialization.serializers;

import li.cil.ceres.Ceres;
import li.cil.ceres.api.DeserializationVisitor;
import li.cil.ceres.api.SerializationException;
import li.cil.ceres.api.SerializationVisitor;
import li.cil.ceres.api.Serializer;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CPUGenerator;

import javax.annotation.Nullable;

public final class R5CPUSerializer implements Serializer<R5CPU> {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<R5CPU> type, final Object value) throws SerializationException {
        assert value.getClass() == R5CPUGenerator.getGeneratedClass();
        final Serializer<?> serializer = Ceres.getSerializer(R5CPUGenerator.getGeneratedClass());
        serializer.serialize(visitor, (Class) R5CPUGenerator.getGeneratedClass(), value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public R5CPU deserialize(final DeserializationVisitor visitor, final Class<R5CPU> type, @Nullable final Object value) throws SerializationException {
        final Serializer<?> serializer = Ceres.getSerializer(R5CPUGenerator.getGeneratedClass());
        return (R5CPU) serializer.deserialize(visitor, (Class) R5CPUGenerator.getGeneratedClass(), value);
    }
}
