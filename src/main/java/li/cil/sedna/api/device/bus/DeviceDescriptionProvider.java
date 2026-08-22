package li.cil.sedna.api.device.bus;

import li.cil.sedna.api.device.Device;

import java.util.List;

@FunctionalInterface
public interface DeviceDescriptionProvider {
    List<DeviceDescription> getDescriptions(final Device device);

    static DeviceDescriptionProvider of(final DeviceDescription description) {
        final List<DeviceDescription> descriptions = List.of(description);
        return device -> descriptions;
    }
}
