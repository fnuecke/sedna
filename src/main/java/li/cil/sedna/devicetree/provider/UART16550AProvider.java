package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;

import java.util.Optional;

public final class UART16550AProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new UART16550AProvider();

    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of("uart");
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node
                .addProp(DevicePropertyNames.COMPATIBLE, "ns16550a")
                .addProp("clock-frequency", 3686400);
    }
}
