package li.cil.sedna.z80;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;

/**
 * Single port controlling whether the board's boot ROM shadows the low address space. Bit 0 set
 * means the ROM is mapped; a board reset maps it again.
 *
 * @see Z80Board#setBootRom(MemoryMappedDevice)
 */
public final class BootRomLatch implements MemoryMappedDevice {
    public static final int LENGTH = 1;

    private final Z80Board board;

    public BootRomLatch(final Z80Board board) {
        this.board = board;
    }

    @Override
    public int getLength() {
        return LENGTH;
    }

    @Override
    public int getSupportedSizes() {
        return 1 << Sizes.SIZE_8_LOG2;
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return board.isBootRomMapped() ? 1 : 0;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        board.setBootRomMapped((value & 1) != 0);
    }
}
