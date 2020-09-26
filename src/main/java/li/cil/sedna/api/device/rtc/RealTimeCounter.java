package li.cil.sedna.api.device.rtc;

import li.cil.sedna.api.device.Device;

public interface RealTimeCounter extends Device {
    long getTime();

    int getFrequency();
}
