package li.cil.sedna.api;

import li.cil.sedna.api.device.InterruptController;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryMap;

public interface Board extends Steppable, Resettable {
    MemoryMap getMemoryMap();

    DeviceBus getDeviceBus();

    InterruptController getInterruptController();

    int getInterruptCount();
}
