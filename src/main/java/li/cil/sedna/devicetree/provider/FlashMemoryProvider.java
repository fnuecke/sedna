package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.*;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.device.flash.FlashMemoryDevice;

import java.util.Optional;

@RegisterDeviceTreeProvider(FlashMemoryDevice.class)
public final class FlashMemoryProvider implements DeviceTreeProvider {
    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of(DeviceNames.FLASH);
    }

    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.find("/soc").getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node
                .addProp(DevicePropertyNames.COMPATIBLE, "cfi-flash")
                .addProp("bank-width", 4);
    }
}
