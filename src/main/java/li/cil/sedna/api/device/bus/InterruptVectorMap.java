package li.cil.sedna.api.device.bus;

/**
 * Translates the interrupt id a device was wired to, as held by
 * {@link li.cil.sedna.api.Interrupt#id}, into whatever a guest on this architecture needs in order
 * to associate an interrupt with that device.
 * <p>
 * The id is architecture-neutral. It is just an opaque id the board associates the device with.
 * This is what allows mapping it to a value the architecture actually wants/needs, which allows
 * keeping devices and {@link li.cil.sedna.api.device.InterruptController}s architecture agnostic.
 * <p>
 * For example, a Z80 in interrupt mode 2 wants the low half of an address in its vector table.
 */
@FunctionalInterface
public interface InterruptVectorMap {
    /**
     * Reported for a device that raises no interrupt, or one that was never wired to a controller.
     */
    int NO_VECTOR = 0xFF;

    int getVector(final int interruptId);
}
