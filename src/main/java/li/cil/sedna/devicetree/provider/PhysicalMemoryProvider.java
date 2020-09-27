package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.devicetree.DeviceNames;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;

import java.util.Optional;

public class PhysicalMemoryProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new PhysicalMemoryProvider();

    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of(DeviceNames.MEMORY);
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        node.addProp(DevicePropertyNames.DEVICE_TYPE, DeviceNames.MEMORY);
    }
}
