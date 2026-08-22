package li.cil.sedna.device.bus.provider;

import li.cil.sedna.api.device.Device;
import li.cil.sedna.api.device.bus.DeviceClass;
import li.cil.sedna.api.device.bus.DeviceDescription;
import li.cil.sedna.api.device.bus.DeviceDescriptionProvider;
import li.cil.sedna.device.disk.WD1793;

import java.util.ArrayList;
import java.util.List;

public final class WD1793DescriptionProvider implements DeviceDescriptionProvider {
    @Override
    public List<DeviceDescription> getDescriptions(final Device device) {
        final WD1793 controller = (WD1793) device;
        final List<DeviceDescription> descriptions = new ArrayList<>();
        for (int unit = 0; unit < controller.getUnitCount(); unit++) {
            descriptions.add(new DeviceDescription(DeviceClass.BLOCK, "FLOPPY", "", unit));
        }
        return descriptions;
    }
}
