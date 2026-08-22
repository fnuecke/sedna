package li.cil.sedna.device;

import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryAccessException;

import java.util.List;

/**
 * Exposes a device under a smaller mapped length than the device itself declares, e.g. to fit a
 * device that pads its reported length to a page into a Z80 board's 256-port I/O space. Stepping
 * and reset are forwarded; the wrapper itself holds no state.
 */
public final class DeviceWindow implements MemoryMappedDevice, Steppable, Resettable, InterruptSource {
    private final MemoryMappedDevice device;
    private final int length;

    public DeviceWindow(final MemoryMappedDevice device, final int length) {
        this.device = device;
        this.length = length;
    }

    public MemoryMappedDevice getDevice() {
        return device;
    }

    @Override
    public int getLength() {
        return length;
    }

    @Override
    public int getSupportedSizes() {
        return device.getSupportedSizes();
    }

    @Override
    public boolean supportsFetch() {
        return device.supportsFetch();
    }

    @Override
    public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
        return device.load(offset, sizeLog2);
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException {
        device.store(offset, value, sizeLog2);
    }

    @Override
    public void step(final int cycles) {
        if (device instanceof final Steppable steppable) {
            steppable.step(cycles);
        }
    }

    @Override
    public void reset() {
        if (device instanceof final Resettable resettable) {
            resettable.reset();
        }
    }

    @Override
    public Iterable<Interrupt> getInterrupts() {
        return device instanceof final InterruptSource source ? source.getInterrupts() : List.of();
    }
}
