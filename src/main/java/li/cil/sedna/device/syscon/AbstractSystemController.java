package li.cil.sedna.device.syscon;

import li.cil.sedna.api.device.MemoryMappedDevice;

public abstract class AbstractSystemController implements MemoryMappedDevice {
    public static final int SYSCON_RESET = 0x0000;
    public static final int SYSCON_POWEROFF = 0x0001;

    @Override
    public int getLength() {
        return 4;
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return 0;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        if (offset == 0) {
            switch ((int) (value & 0xFFFF)) {
                case SYSCON_RESET: {
                    handleReset();
                    break;
                }

                case SYSCON_POWEROFF: {
                    handlePowerOff();
                    break;
                }
            }
        }
    }

    protected abstract void handleReset();

    protected abstract void handlePowerOff();
}
