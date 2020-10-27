package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.devicetree.*;
import li.cil.sedna.api.memory.MemoryMap;

import java.util.Optional;

@RegisterDeviceTreeProvider(PhysicalMemory.class)
public class PhysicalMemoryProvider implements DeviceTreeProvider {
    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of(DeviceNames.MEMORY);
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node.addProp(DevicePropertyNames.DEVICE_TYPE, DeviceNames.MEMORY);
    }
}
