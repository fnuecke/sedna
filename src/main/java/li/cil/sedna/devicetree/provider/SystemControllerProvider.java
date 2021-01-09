package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.DeviceNames;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.device.syscon.AbstractSystemController;

import java.util.Optional;

public final class SystemControllerProvider implements DeviceTreeProvider {
    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of(DeviceNames.SYSCON);
    }

    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.find("/soc").getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        final int handle = node.getPHandle(device);
        node
                .addProp(DevicePropertyNames.COMPATIBLE, "syscon")
                .addProp(DevicePropertyNames.PHANDLE, handle);

        final DeviceTree soc = node.find("/soc");
        soc.putChild("reboot", reboot -> reboot
                .addProp(DevicePropertyNames.COMPATIBLE, "syscon-reboot")
                .addProp("regmap", handle)
                .addProp("offset", 0)
                .addProp("value", AbstractSystemController.SYSCON_RESET));
        soc.putChild("poweroff", poweroff -> poweroff
                .addProp(DevicePropertyNames.COMPATIBLE, "syscon-poweroff")
                .addProp("regmap", handle)
                .addProp("offset", 0)
                .addProp("value", AbstractSystemController.SYSCON_POWEROFF));
    }
}
