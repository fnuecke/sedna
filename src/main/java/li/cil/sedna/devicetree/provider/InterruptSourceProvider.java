package li.cil.sedna.devicetree.provider;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.InterruptController;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryMap;

public final class InterruptSourceProvider implements DeviceTreeProvider {
    public static final InterruptSourceProvider INSTANCE = new InterruptSourceProvider();

    @Override
    public void visit(final DeviceTree node, final MemoryMap memoryMap, final Device device) {
        final InterruptSource interruptSource = (InterruptSource) device;
        final IntList interrupts = new IntArrayList();
        for (final Interrupt interrupt : interruptSource.getInterrupts()) {
            final InterruptController controller = interrupt.controller;
            if (controller != null) {
                interrupts.add(node.getPHandle(controller));
                interrupts.add(interrupt.id);
            }
        }
        node.addProp("interrupts-extended", interrupts.toArray());
    }
}
