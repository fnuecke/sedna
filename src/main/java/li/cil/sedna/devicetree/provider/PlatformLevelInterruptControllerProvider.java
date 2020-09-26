package li.cil.sedna.devicetree.provider;

import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.devicetree.DeviceNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.riscv.device.R5PlatformLevelInterruptController;

import java.util.Optional;

public final class PlatformLevelInterruptControllerProvider implements DeviceTreeProvider {
    public static final DeviceTreeProvider INSTANCE = new PlatformLevelInterruptControllerProvider();

    @Override
    public Optional<String> getName(final Device device) {
        return Optional.of("plic");
    }

    @Override
    public Optional<DeviceTree> createNode(final DeviceTree root, final MemoryMap memoryMap, final Device device, final String deviceName) {
        final Optional<MemoryRange> range = memoryMap.getMemoryRange((MemoryMappedDevice) device);
        return range.map(r -> root.find("/soc").getChild(deviceName, r.address()));
    }

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        final R5PlatformLevelInterruptController plic = (R5PlatformLevelInterruptController) device;
        node
                .addProp("#address-cells", 0)
                .addProp("#interrupt-cells", 1)
                .addProp(DeviceNames.INTERRUPT_CONTROLLER)
                .addProp(DevicePropertyNames.COMPATIBLE, "riscv,plic0")
                .addProp("riscv,ndev", 31)
                .addProp(DevicePropertyNames.PHANDLE, node.createPHandle(plic));
    }
}
