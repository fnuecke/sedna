package li.cil.sedna.api.vm.device.rtc;

import li.cil.sedna.api.vm.device.Device;

public interface RealTimeCounter extends Device {
    long getTime();

    int getFrequency();
}
