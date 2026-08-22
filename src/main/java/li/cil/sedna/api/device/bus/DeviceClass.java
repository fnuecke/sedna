package li.cil.sedna.api.device.bus;

/**
 * What kind of thing a device is, as a guest sees it.
 * <p>
 * <b>Value ranges:</b> {@code 00}-{@code 7F} belong to Sedna, {@code 80}-{@code FF}, i.e. from
 * {@link #THIRD_PARTY_BASE} up, are free to allocate for classes Sedna knows nothing about.
 */
public record DeviceClass(int value) {
    /**
     * The first value Sedna will never allocate.
     */
    public static final int THIRD_PARTY_BASE = 0x80;

    public DeviceClass {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Device class does not fit a byte: " + value);
        }
    }

    public static final DeviceClass UNKNOWN = new DeviceClass(0x00);
    public static final DeviceClass BUS = new DeviceClass(0x01);
    public static final DeviceClass CHARACTER = new DeviceClass(0x02);
    public static final DeviceClass BLOCK = new DeviceClass(0x03);
    public static final DeviceClass RTC = new DeviceClass(0x04);
    public static final DeviceClass GPIO = new DeviceClass(0x05);
    public static final DeviceClass SCREEN = new DeviceClass(0x06);
    public static final DeviceClass BOOT_ROM = new DeviceClass(0x07);
}
