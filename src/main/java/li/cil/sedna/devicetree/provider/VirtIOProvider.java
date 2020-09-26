package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;

import java.util.Optional;

public final class VirtIOProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new VirtIOProvider();

    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of("virtio");
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node.addProp(DevicePropertyNames.COMPATIBLE, "virtio,mmio");
    }
}
