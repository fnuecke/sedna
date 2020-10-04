package li.cil.sedna.serialization.serializers;

import li.cil.ceres.api.*;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;

@RegisterSerializer
public final class AtomicIntegerSerializer implements Serializer<AtomicInteger> {
    @Override
    public void serialize(final SerializationVisitor visitor, final Class<AtomicInteger> type, final Object value) throws SerializationException {
        visitor.putInt("value", ((AtomicInteger) value).intValue());
    }

    @Override
    public AtomicInteger deserialize(final DeserializationVisitor visitor, final Class<AtomicInteger> type, @Nullable final Object value) throws SerializationException {
        AtomicInteger typedValue = (AtomicInteger) value;
        if (visitor.exists("value")) {
            if (typedValue == null) {
                typedValue = new AtomicInteger();
            }
            typedValue.set(visitor.getInt("value"));
        }
        return typedValue;
    }
}
