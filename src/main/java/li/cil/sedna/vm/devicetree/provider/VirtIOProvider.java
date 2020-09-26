package li.cil.sedna.vm.devicetree.provider;

import li.cil.sedna.api.vm.MemoryMap;
import li.cil.sedna.api.vm.device.Device;
import li.cil.sedna.api.vm.devicetree.DeviceTree;
import li.cil.sedna.api.vm.devicetree.DeviceTreePropertyNames;
import li.cil.sedna.api.vm.devicetree.DeviceTreeProvider;

import java.util.Optional;

public final class VirtIOProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new VirtIOProvider();

    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of("virtio");
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node.addProp(DeviceTreePropertyNames.COMPATIBLE, "virtio,mmio");
    }
}
