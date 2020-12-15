package li.cil.sedna.api.device;

public interface Device {
    /**
     * Get the underlying object of some device.
     * <p>
     * This is used when assigning properties to devices. Overriding this allows
     * implementing wrappers for devices, such as interrupt-controllers, which is
     * useful for encapsulating access contexts.
     *
     * @return the underlying identity of this device.
     */
    default Object getIdentity() {
        return this;
    }
}
