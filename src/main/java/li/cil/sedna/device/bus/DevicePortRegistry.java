package li.cil.sedna.device.bus;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import org.apache.commons.lang3.ClassUtils;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

public final class DevicePortRegistry {
    private static final Map<Class<?>, Integer> WIDTHS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, OptionalInt> CACHE = new ConcurrentHashMap<>();

    public static void putWidth(final Class<? extends Device> type, final int ports) {
        if (ports <= 0 || ports > 0x100) {
            throw new IllegalArgumentException("Port width does not fit a port space: " + ports);
        }

        synchronized (CACHE) {
            WIDTHS.put(type, ports);
            CACHE.clear();
        }
    }

    public static OptionalInt getWidth(final MemoryMappedDevice device) {
        return getWidth(device.getClass());
    }

    private static OptionalInt getWidth(final Class<?> type) {
        final OptionalInt cached = CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        synchronized (CACHE) {
            // Other thread may have gotten here before us, check again.
            final OptionalInt cachedAgain = CACHE.get(type);
            if (cachedAgain != null) {
                return cachedAgain;
            }

            final OptionalInt result = findWidth(type);
            CACHE.put(type, result);
            return result;
        }
    }

    private static OptionalInt findWidth(final Class<?> type) {
        for (final Class<?> current : ClassUtils.hierarchy(type, ClassUtils.Interfaces.INCLUDE)) {
            final Integer width = WIDTHS.get(current);
            if (width != null) {
                return OptionalInt.of(width);
            }
        }

        return OptionalInt.empty();
    }

    private DevicePortRegistry() {
    }
}
