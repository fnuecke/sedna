package li.cil.sedna.api.device.bus;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Device information provided to guest systems via the device enumerator.
 * <p>
 * {@link DeviceDescription#attributes()} are <em>class</em>-specific:
 * <ul>
 *     <li>{@link DeviceClass#BLOCK}: low nibble is the unit on its controller, to address
 *     multiple drives behind one controller. High nibble is the media type.</li>
 *     <li>every other class: unused, reads {@code 0}.</li>
 * </ul>
 *
 * @param deviceClass what the guest uses to decide how to talk to the device.
 * @param names       the names of the device. The first is treated as the primary <em>type</em> name, e.g.
 *                    {@code FLOPPY} for floppy drives; additional names are aliases, such as a label a
 *                    user gave the device. Guests read them in order, each terminated by a zero, with an
 *                    empty name ending the list.
 * @param id          stable identity across sessions, for hosts that have one. Informational;
 *                    enumeration order comes from the order devices were added to the board.
 * @param attributes  class-specific byte encoding additional per-instance information.
 */
public record DeviceDescription(DeviceClass deviceClass,
                                List<String> names,
                                String id,
                                int attributes) {
    public DeviceDescription {
        requireByte(attributes, "attributes");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Device has no name.");
        }
        names = List.copyOf(new LinkedHashSet<>(names));
        for (final String name : names) {
            if (!isValidName(name)) {
                throw new IllegalArgumentException("Device name is empty or not printable ASCII: [" + name + "]");
            }
        }
        requirePrintableAscii(id, "id");
    }

    public DeviceDescription(final DeviceClass deviceClass, final String name, final String id, final int attributes) {
        this(deviceClass, List.of(name), id, attributes);
    }

    public DeviceDescription(final DeviceClass deviceClass, final String name) {
        this(deviceClass, name, "", 0);
    }

    public String name() {
        return names.getFirst();
    }

    public static boolean isValidName(final String name) {
        return !name.isEmpty() && isPrintableAscii(name);
    }

    private static void requireByte(final int value, final String what) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Device " + what + " does not fit a byte: " + value);
        }
    }

    private static void requirePrintableAscii(final String value, final String what) {
        if (!isPrintableAscii(value)) {
            throw new IllegalArgumentException("Device " + what + " is not printable ASCII: " + value);
        }
    }

    private static boolean isPrintableAscii(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char character = value.charAt(i);
            if (character < 0x20 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
