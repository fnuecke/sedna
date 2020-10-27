package li.cil.sedna.devicetree;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.devicetree.RegisterDeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.Reflections;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

public final class DeviceTreeRegistry {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Map<Class<? extends Device>, DeviceTreeProvider> PROVIDERS = new HashMap<>();
    private static final Map<Class<? extends Device>, DeviceTreeProvider> PROVIDER_CACHE = new HashMap<>();

    static {
        addProvidersFrom(DeviceTreeRegistry.class.getClassLoader());
    }

    public static void addProvidersFrom(final ClassLoader classLoader) {
        for (final Class<? extends DeviceTreeProvider> type : new Reflections(classLoader).getSubTypesOf(DeviceTreeProvider.class)) {
            if (!type.isAnnotationPresent(RegisterDeviceTreeProvider.class)) {
                continue;
            }

            final Class<? extends Device> targetType = type.getAnnotation(RegisterDeviceTreeProvider.class).value();

            try {
                final DeviceTreeProvider provider = type.newInstance();
                addProvider(targetType, provider);
            } catch (final InstantiationException | IllegalAccessException e) {
                LOGGER.error("Failed instantiating device tree provider [{}]: {}", type, e);
            }
        }
    }

    public static void addProvider(final Class<? extends Device> type, final DeviceTreeProvider provider) {
        PROVIDERS.put(type, provider);
        PROVIDER_CACHE.clear();
    }

    private static void visitBaseTypes(@Nullable final Class<?> type, final Consumer<Class<?>> visitor) {
        if (type == null) {
            return;
        }

        visitor.accept(type);
        visitBaseTypes(type.getSuperclass(), visitor);

        final Class<?>[] interfaces = type.getInterfaces();
        for (final Class<?> iface : interfaces) {
            visitor.accept(iface);
            visitBaseTypes(iface, visitor);
        }
    }

    @Nullable
    public static DeviceTreeProvider getProvider(final Device device) {
        final Class<? extends Device> deviceClass = device.getClass();
        if (PROVIDER_CACHE.containsKey(deviceClass)) {
            return PROVIDER_CACHE.get(deviceClass);
        }

        final List<DeviceTreeProvider> relevant = new ArrayList<>();
        final Set<Class<?>> seen = new HashSet<>();
        visitBaseTypes(deviceClass, c -> {
            if (seen.add(c) && PROVIDERS.containsKey(c)) {
                relevant.add(PROVIDERS.get(c));
            }
        });

        if (relevant.size() == 0) {
            return null;
        }

        if (relevant.size() == 1) {
            return relevant.get(0);
        }

        // Flip things around so when iterating in visit() we go from least to most specific provider.
        Collections.reverse(relevant);

        return new DeviceTreeProvider() {
            @Override
            public Optional<String> getName(final Device device) {
                for (int i = relevant.size() - 1; i >= 0; i--) {
                    final Optional<String> name = relevant.get(i).getName(device);
                    if (name.isPresent()) {
                        return name;
                    }
                }

                return Optional.empty();
            }

            @Override
            public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
                for (int i = relevant.size() - 1; i >= 0; i--) {
                    final Optional<DeviceTree> node = relevant.get(i).createNode(root, memoryMap, device, deviceName);
                    if (node.isPresent()) {
                        return node;
                    }
                }

                return Optional.empty();
            }

            @Override
            public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
                for (final DeviceTreeProvider provider : relevant) {
                    provider.visit(node, memoryMap, device);
                }
            }
        };
    }

    public static void visit(final DeviceTree root, final MemoryMap memoryMap, final Device device) {
        final DeviceTreeProvider provider = getProvider(device);
        if (provider == null) {
            LOGGER.warn("No provider for device [{}].", device);
            return;
        }

        final Optional<String> name = provider.getName(device);
        if (!name.isPresent()) {
            LOGGER.warn("Failed obtaining name for device [{}].", device);
            return;
        }

        final Optional<DeviceTree> node = provider.createNode(root, memoryMap, device, name.get());
        if (!node.isPresent()) {
            LOGGER.warn("Failed obtaining node for device [{}].", device);
            return;
        }

        provider.visit(node.get(), memoryMap, device);
    }

    public static DeviceTree create(final MemoryMap mmu) {
        return new DeviceTreeImpl(mmu);
    }
}
