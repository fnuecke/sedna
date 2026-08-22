package li.cil.sedna.api.device.bus;

/**
 * What a device tells a guest about itself through a device enumerator.
 * <p>
 * Fields are sent to guests as-is, so the constructor validates size and format.
 * <p>
 * {@link DeviceDescription#attributes()} are <em>class</em>-specific:
 * <ul>
 *     <li>{@link DeviceClass#BLOCK}: low nibble is the unit on its controller, to address
 *     multiple drives behind one controller. High nibble is the media type.</li>
 *     <li>every other class: unused, reads {@code 0}.</li>
 * </ul>
 *
 * @param deviceClass what the guest uses to decide how to talk to the device.
 * @param name        a <em>type</em> name. E.g. {@code FLOPPY} for floppy drives.
 * @param id          stable identity across sessions, for hosts that have one. Informational;
 *                    enumeration order comes from the order devices were added to the board.
 * @param attributes  class-specific byte encoding additional per-instance information.
 */
public record DeviceDescription(DeviceClass deviceClass,
                                String name,
                                String id,
                                int attributes) {
    public DeviceDescription {
        requireByte(attributes, "attributes");
        requirePrintableAscii(name, "name");
        requirePrintableAscii(id, "id");
    }

    public DeviceDescription(final DeviceClass deviceClass, final String name) {
        this(deviceClass, name, "", 0);
    }

    private static void requireByte(final int value, final String what) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Device " + what + " does not fit a byte: " + value);
        }
    }

    private static void requirePrintableAscii(final String value, final String what) {
        for (int i = 0; i < value.length(); i++) {
            final char character = value.charAt(i);
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException("Device " + what + " is not printable ASCII: " + value);
            }
        }
    }
}
