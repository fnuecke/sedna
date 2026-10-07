package li.cil.sedna.api.device.bus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class DeviceDescriptionTests {
    @Test
    public void firstNameIsDeviceName() {
        final DeviceDescription description = new DeviceDescription(DeviceClass.CHARACTER, List.of("UART", "console"), "", 0);
        assertEquals("UART", description.name());
    }

    @Test
    public void singleNameConstructorListsJustThatName() {
        assertEquals(List.of("UART"), new DeviceDescription(DeviceClass.CHARACTER, "UART").names());
    }

    @Test
    public void duplicateNamesAreDropped() {
        final DeviceDescription description = new DeviceDescription(DeviceClass.CHARACTER, List.of("UART", "console", "UART", "console"), "", 0);
        assertEquals(List.of("UART", "console"), description.names());
    }

    @Test
    public void emptyNameListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceDescription(DeviceClass.CHARACTER, List.of(), "", 0));
    }

    @Test
    public void emptyNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceDescription(DeviceClass.CHARACTER, ""));
        assertThrows(IllegalArgumentException.class, () -> new DeviceDescription(DeviceClass.CHARACTER, List.of("UART", ""), "", 0));
    }

    @Test
    public void validNamesArePrintableAsciiAndNotEmpty() {
        assertTrue(DeviceDescription.isValidName("lamp_ctl"));
        assertFalse(DeviceDescription.isValidName(""));
        assertFalse(DeviceDescription.isValidName("mötör"));
        assertFalse(DeviceDescription.isValidName("tab\there"));
    }

    @Test
    public void nonAsciiNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceDescription(DeviceClass.CHARACTER, List.of("UART", "mötör"), "", 0));
    }
}
