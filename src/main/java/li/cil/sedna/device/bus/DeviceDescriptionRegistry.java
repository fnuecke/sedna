package li.cil.sedna.device.bus;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.bus.DeviceDescription;
import li.cil.sedna.api.device.bus.DeviceDescriptionProvider;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeviceDescriptionRegistry {
    private static final Map<Class<?>, DeviceDescriptionProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<DeviceDescriptionProvider>> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger generation = new AtomicInteger();

    public static void putProvider(final Class<? extends Device> type, final DeviceDescriptionProvider provider) {
        PROVIDERS.put(type, provider);
        generation.incrementAndGet();
        CACHE.clear();
    }

    public static List<DeviceDescription> getDescriptions(final Device device) {
        return getProvider(device.getClass())
            .map(provider -> provider.getDescriptions(device))
            .orElse(List.of());
    }

    private static Optional<DeviceDescriptionProvider> getProvider(final Class<?> type) {
        final Optional<DeviceDescriptionProvider> cached = CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        final int before = generation.get();
        final Optional<DeviceDescriptionProvider> result = Optional.ofNullable(findProvider(type));
        if (generation.get() == before) {
            CACHE.put(type, result);
        }
        return result;
    }

    @Nullable
    private static DeviceDescriptionProvider findProvider(final Class<?> type) {
        final Queue<Class<?>> pending = new ArrayDeque<>();
        final Set<Class<?>> seen = new HashSet<>();
        pending.add(type);
        seen.add(type);
        while (!pending.isEmpty()) {
            final Class<?> current = pending.remove();
            final DeviceDescriptionProvider provider = PROVIDERS.get(current);
            if (provider != null) {
                return provider;
            }

            final Class<?> superclass = current.getSuperclass();
            if (superclass != null && seen.add(superclass)) {
                pending.add(superclass);
            }
            for (final Class<?> iface : current.getInterfaces()) {
                if (seen.add(iface)) {
                    pending.add(iface);
                }
            }
        }
        return null;
    }

    private DeviceDescriptionRegistry() {
    }
}
