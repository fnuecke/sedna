package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;

import java.util.Optional;

public class MemoryMappedDeviceProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new MemoryMappedDeviceProvider();

    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        final MemoryMappedDevice mappedDevice = (MemoryMappedDevice) device;
        final Optional<MemoryRange> range = memoryMap.getMemoryRange(mappedDevice);

        // TODO in the future when we may want to change bus widths check parent for cell and size cell num.
        range.ifPresent(r -> node.addProp(DevicePropertyNames.REG,
                ((long) r.address()) & 0xFFFFFFFFL,
                ((long) r.size()) & 0xFFFFFFFFL));
    }
}
