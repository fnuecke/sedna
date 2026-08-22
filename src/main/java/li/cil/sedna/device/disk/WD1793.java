package li.cil.sedna.device.disk;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.Resettable;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A WD1793-style floppy disk controller.
 * <p>
 * Five byte-wide registers:
 * <pre>
 * 0: status (read) / command (write)
 * 1: track register
 * 2: sector register (1-based)
 * 3: data register
 * 4: IRQ/DRQ flags (read) / system register: drive select, side, reset, halt (write)
 * </pre>
 * <p>
 * Media is attached via {@link #setDisk}, which also defines the geometry; the disk image is laid
 * out side-major (side, track, sector). Attach media before restoring serialized state, so a
 * transfer interrupted by a save resumes against the restored buffer.
 */
@Serialized
public final class WD1793 implements MemoryMappedDevice, Resettable {
    private static final int S_BUSY = 0x01;
    private static final int S_INDEX = 0x02;
    private static final int S_DATA_REQUEST = 0x02;
    private static final int S_TRACK0 = 0x04;
    private static final int S_LOST_DATA = 0x04;
    private static final int S_SEEK_ERROR = 0x10;
    private static final int S_RECORD_NOT_FOUND = 0x10;
    private static final int S_HEAD_LOADED = 0x20;
    private static final int S_WRITE_PROTECT = 0x40;
    private static final int S_NOT_READY = 0x80;

    private static final int SC_DRIVE = 0x03;
    private static final int SC_RESET = 0x04;
    private static final int SC_SIDE = 0x10;

    private static final int C_ARG_LOAD_HEAD = 0x08;
    private static final int C_ARG_SIDE_COMPARE = 0x02;
    private static final int C_ARG_SIDE_FLAG = 0x08;
    private static final int C_ARG_MULTIPLE = 0x10;
    private static final int C_ARG_IMMEDIATE_IRQ = 0x08;
    private static final int C_ARG_SET_TRACK = 0x10;

    private static final int C_RESTORE = 0x00;
    private static final int C_SEEK = 0x10;
    private static final int C_STEP = 0x20;
    private static final int C_STEP_UPDATE = C_STEP | C_ARG_SET_TRACK;
    private static final int C_STEP_IN = 0x40;
    private static final int C_STEP_IN_UPDATE = C_STEP_IN | C_ARG_SET_TRACK;
    private static final int C_STEP_OUT = 0x60;
    private static final int C_STEP_OUT_UPDATE = C_STEP_OUT | C_ARG_SET_TRACK;
    private static final int C_READ_SECTOR = 0x80;
    private static final int C_READ_SECTOR_MULTIPLE = C_READ_SECTOR | C_ARG_MULTIPLE;
    private static final int C_WRITE_SECTOR = 0xA0;
    private static final int C_WRITE_SECTOR_MULTIPLE = C_WRITE_SECTOR | C_ARG_MULTIPLE;
    private static final int C_READ_ADDRESS = 0xC0;
    private static final int C_FORCE_INTERRUPT = 0xD0;
    private static final int C_READ_TRACK = 0xE0;
    private static final int C_WRITE_TRACK = 0xF0;

    private static final int FLAG_IRQ = 0x80;
    private static final int FLAG_DRQ = 0x40;

    private static final int LOST_DATA_TIMEOUT = 255;

    private int status, track, sector, data, system;
    private int drive, side, ioSide;
    private int irqFlags;
    private int lastStep = C_STEP_IN;
    private int readsLeft, writesLeft;
    private int waitTimeout;
    private byte[] buffer = new byte[0];
    private int bufferIndex;

    private transient BlockDevice disk;
    private transient int sides, tracks, sectorsPerTrack, sectorSize;

    public void setDisk(final BlockDevice disk, final int sides, final int tracks, final int sectorsPerTrack, final int sectorSize) {
        this.disk = disk;
        this.sides = sides;
        this.tracks = tracks;
        this.sectorsPerTrack = sectorsPerTrack;
        this.sectorSize = sectorSize;
        buffer = new byte[sectorSize];
        reset();
    }

    public void removeDisk() {
        disk = null;
        reset();
    }

    @Override
    public void reset() {
        status = track = sector = data = 0;
        system = SC_RESET;
        drive = side = ioSide = 0;
        irqFlags = 0;
        lastStep = C_STEP_IN;
        readsLeft = writesLeft = 0;
        waitTimeout = 0;
        bufferIndex = 0;
    }

    // ------------------------------------------------------------- //
    // MemoryMappedDevice

    @Override
    public int getLength() {
        return 5;
    }

    @Override
    public int getSupportedSizes() {
        return 1 << Sizes.SIZE_8_LOG2;
    }

    @Override
    public long load(final int offset, final int sizeLog2) {
        return switch (offset) {
            case 0 -> readStatus();
            case 1 -> track;
            case 2 -> (sector + 1) & 0xFF;
            case 3 -> readData();
            case 4 -> readFlags();
            default -> 0xFF;
        };
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) {
        final int value8 = (int) value & 0xFF;
        switch (offset) {
            case 0 -> writeCommand(value8);
            case 1 -> {
                if (!isBusy()) {
                    track = value8;
                }
            }
            case 2 -> {
                if (!isBusy()) {
                    sector = (value8 - 1) & 0xFF;
                }
            }
            case 3 -> writeData(value8);
            case 4 -> writeSystem(value8);
        }
    }

    // ------------------------------------------------------------- //

    private int readStatus() {
        // Status bits are held until the next command; reading only deasserts the IRQ line.
        irqFlags &= ~FLAG_IRQ;

        int result = status;
        if (getDisk() == null) {
            result |= S_NOT_READY;
        }
        return result;
    }

    private void writeCommand(final int value) {
        irqFlags &= ~FLAG_IRQ;

        final int command = value & 0xF0;

        if (isBusy() && command != C_FORCE_INTERRUPT) {
            return;
        }

        switch (command) {
            case C_RESTORE -> seekTrack(0, value, true);
            case C_SEEK -> seekTrack(data, value, true);
            case C_STEP, C_STEP_UPDATE -> {
                final int target = lastStep == C_STEP_IN ? track + 1 : track - 1;
                seekTrack(target, value, (value & C_ARG_SET_TRACK) != 0);
            }
            case C_STEP_IN, C_STEP_IN_UPDATE -> {
                lastStep = C_STEP_IN;
                seekTrack(track + 1, value, (command & C_ARG_SET_TRACK) != 0);
            }
            case C_STEP_OUT, C_STEP_OUT_UPDATE -> {
                lastStep = C_STEP_OUT;
                seekTrack(track - 1, value, (command & C_ARG_SET_TRACK) != 0);
            }
            case C_READ_SECTOR, C_READ_SECTOR_MULTIPLE -> {
                status = S_BUSY;
                readsLeft = beginTransfer(value);
                if (readsLeft > 0 && !loadSector()) {
                    abortTransfer(S_RECORD_NOT_FOUND);
                }
            }
            case C_WRITE_SECTOR, C_WRITE_SECTOR_MULTIPLE -> {
                status = S_BUSY;
                final BlockDevice disk = getDisk();
                if (disk != null && disk.isReadonly()) {
                    status = S_WRITE_PROTECT;
                    setInterrupt();
                } else {
                    writesLeft = beginTransfer(value);
                    bufferIndex = 0;
                }
            }
            case C_READ_ADDRESS, C_READ_TRACK, C_WRITE_TRACK -> {
                status = S_RECORD_NOT_FOUND;
                setInterrupt();
            }
            case C_FORCE_INTERRUPT -> {
                readsLeft = writesLeft = 0;
                waitTimeout = 0;
                if (isBusy()) {
                    status &= ~(S_BUSY | S_DATA_REQUEST);
                } else {
                    status = track == 0 ? S_TRACK0 : 0;
                }
                if ((value & C_ARG_IMMEDIATE_IRQ) != 0) {
                    setInterrupt();
                }
            }
        }
    }

    private void seekTrack(final int target, final int command, final boolean updateRegister) {
        final BlockDevice disk = getDisk();
        if (disk == null || target < 0 || target >= tracks) {
            status = S_SEEK_ERROR;
            setInterrupt();
            return;
        }

        status = S_INDEX;
        if (target == 0) {
            status |= S_TRACK0;
        }
        if ((command & C_ARG_LOAD_HEAD) != 0) {
            status |= S_HEAD_LOADED;
        }
        if (disk.isReadonly()) {
            status |= S_WRITE_PROTECT;
        }
        if (updateRegister) {
            track = target;
        }

        setInterrupt();
    }

    private int beginTransfer(final int command) {
        final BlockDevice disk = getDisk();
        if (disk == null) {
            status = S_RECORD_NOT_FOUND;
            setInterrupt();
            return 0;
        }

        if ((command & C_ARG_SIDE_COMPARE) != 0) {
            ioSide = (command & C_ARG_SIDE_FLAG) != 0 ? 1 : 0;
        } else {
            ioSide = side;
        }

        if (ioSide >= sides || track >= tracks || sector >= sectorsPerTrack) {
            status = S_RECORD_NOT_FOUND;
            setInterrupt();
            return 0;
        }

        status |= S_DATA_REQUEST;
        irqFlags = FLAG_DRQ;
        waitTimeout = LOST_DATA_TIMEOUT;

        if ((command & C_ARG_MULTIPLE) != 0) {
            return (sectorsPerTrack - sector) * sectorSize;
        } else {
            return sectorSize;
        }
    }

    private int readData() {
        if (readsLeft > 0) {
            // Media may have changed geometry across a save/load boundary mid-transfer.
            if (buffer.length != sectorSize) {
                abortTransfer(S_LOST_DATA);
                return 0xFF;
            }

            if (bufferIndex == sectorSize) {
                sector++;
                if (!loadSector()) {
                    abortTransfer(S_RECORD_NOT_FOUND);
                    return 0xFF;
                }
            }

            data = buffer[bufferIndex++] & 0xFF;
            if (--readsLeft > 0) {
                waitTimeout = LOST_DATA_TIMEOUT;
            } else {
                finishTransfer();
            }
        }

        return data;
    }

    private void writeData(final int value) {
        data = value;

        if (writesLeft > 0) {
            if (buffer.length != sectorSize) {
                abortTransfer(S_LOST_DATA);
                return;
            }

            buffer[bufferIndex++] = (byte) value;
            final boolean sectorComplete = bufferIndex == sectorSize;
            if (sectorComplete && !storeSector()) {
                abortTransfer(S_RECORD_NOT_FOUND);
                return;
            }

            if (--writesLeft > 0) {
                waitTimeout = LOST_DATA_TIMEOUT;
                if (sectorComplete) {
                    sector++;
                    bufferIndex = 0;
                }
            } else {
                finishTransfer();
            }
        }
    }

    private int readFlags() {
        if ((readsLeft | writesLeft) != 0 && waitTimeout > 0 && --waitTimeout == 0) {
            abortTransfer(S_LOST_DATA);
        }
        return irqFlags;
    }

    private void writeSystem(final int value) {
        if (((system ^ value) & value & SC_RESET) != 0) {
            reset();
        }
        drive = value & SC_DRIVE;
        side = (value & SC_SIDE) == 0 ? 0 : 1;
        system = value;
    }

    // ------------------------------------------------------------- //

    @Nullable
    private BlockDevice getDisk() {
        return drive == 0 ? disk : null;
    }

    private boolean isBusy() {
        return (status & S_BUSY) != 0;
    }

    private void finishTransfer() {
        status &= ~(S_DATA_REQUEST | S_BUSY);
        setInterrupt();
    }

    private void abortTransfer(final int statusBits) {
        readsLeft = writesLeft = 0;
        status = statusBits;
        setInterrupt();
    }

    private void setInterrupt() {
        irqFlags = FLAG_IRQ;
    }

    private long sectorOffset() {
        return (((long) ioSide * tracks + track) * sectorsPerTrack + sector) * sectorSize;
    }

    private boolean loadSector() {
        final BlockDevice disk = getDisk();
        if (disk == null || sector >= sectorsPerTrack) {
            return false;
        }
        try (final InputStream stream = disk.getInputStream(sectorOffset())) {
            if (stream.readNBytes(buffer, 0, sectorSize) < sectorSize) {
                return false;
            }
        } catch (final IOException e) {
            return false;
        }
        bufferIndex = 0;
        return true;
    }

    private boolean storeSector() {
        final BlockDevice disk = getDisk();
        if (disk == null || sector >= sectorsPerTrack) {
            return false;
        }
        try (final OutputStream stream = disk.getOutputStream(sectorOffset())) {
            stream.write(buffer, 0, sectorSize);
        } catch (final IOException e) {
            return false;
        }
        return true;
    }
}
