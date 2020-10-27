package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.devicetree.RegisterDeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.riscv.device.R5CoreLocalInterrupter;

import java.util.Optional;

@RegisterDeviceTreeProvider(R5CoreLocalInterrupter.class)
public final class CoreLocalInterrupterProvider implements DeviceTreeProvider {
    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of("clint");
    }

    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.find("/soc").getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node.addProp(DevicePropertyNames.COMPATIBLE, "riscv,clint0");
    }
}
