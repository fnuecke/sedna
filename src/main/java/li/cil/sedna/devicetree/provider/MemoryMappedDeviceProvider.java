package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;

import java.util.Optional;

public class MemoryMappedDeviceProvider implements DeviceTreeProvider {
    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        final MemoryMappedDevice mappedDevice = (MemoryMappedDevice) device;
        final Optional<MemoryRange> range = memoryMap.getMemoryRange(mappedDevice);

        range.ifPresent(r -> node.addProp(DevicePropertyNames.REG,
                r.address() & 0xFFFFFFFFL,
                r.size() & 0xFFFFFFFFL));
    }
}
