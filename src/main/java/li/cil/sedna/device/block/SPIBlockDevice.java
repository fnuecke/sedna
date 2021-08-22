package li.cil.sedna.device.block;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.device.BlockDevice;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import org.apache.commons.io.IOUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static java.util.Collections.singleton;

/**
 * Emulates an MMC block device that can be controlled in SPI mode.
 * <p>
 * This is a lie, since we directly memory map this... but we just use this
 * in eLua, so we can just skip having an SPI controller.
 */
public class SPIBlockDevice implements InterruptSource, MemoryMappedDevice, Closeable {
    private static final int CMD_MASK = 0x3F; // Command id is 0-63.

    // Via http://elm-chan.org/docs/mmc/mmc_e.html
    private static final int CMD0_GO_IDLE_STATE = 0;          // Software reset.
    private static final int CMD1_SEND_OP_COND = 1;           // Initiate initialization process.
    private static final int CMD9_SEND_CSD = 9;               // Read CSD register.
    private static final int CMD10_SEND_CID = 10;             // Read CID register.
    private static final int CMD12_STOP_TRANSMISSION = 12;    // Stop reading multiple blocks.
    private static final int CMD16_SET_BLOCKLEN = 16;         // Change R/W block size.
    private static final int CMD17_READ_SINGLE_BLOCK = 17;    // Read a block.
    private static final int CMD18_READ_MULTIPLE_BLOCK = 18;  // Begin reading multiple blocks.
    private static final int CMD24_WRITE_SINGLE_BLOCK = 24;   // Write a block.
    private static final int CMD25_WRITE_BLOCK_MULTIPLE = 25; // Write multiple blocks.

    private static final int R0_READY = 0xFF;
    private static final int R1_IDLE = 0b00000001;
    private static final int R1_ERASE_RESET = 0b0000_0010;
    private static final int R1_ILLEGAL_COMMAND = 0b00000100;
    private static final int R1_COMMAND_CRC_ERROR = 0b00001000;
    private static final int R1_ERASE_SEQUENCE_ERROR = 0b00010000;
    private static final int R1_ADDRESS_ERROR = 0b00100000;
    private static final int R1_PARAMETER_ERROR = 0b01000000;

    private static final int DATA_TOKEN_17_18_24 = 0b11111110;
    private static final int DATA_TOKEN_25 = 0b11111100;
    private static final int STOP_TOKEN_25 = 0b11111101;
    private static final int ERROR_OUT_OF_RANGE = 0b00001000;
    private static final int DATA_RESPONSE_ACCEPTED = 0b11100101;
    private static final int DATA_RESPONSE_WRITE_ERROR = 0b11101101;

    private enum State {
        DEFAULT,  // Base state, processing commands.
        READING0, // Response to read request, then go to READING2.
        READING1, // Sending data packets (one or more).
        WRITING0, // Response to write request, then go to WRITING2.
        WRITING1, // Receiving data packets (one or more).
    }

    private final BlockDevice block;
    private final Interrupt interrupt = new Interrupt();
    @Serialized private int blockLength = 512;
    @Serialized private State state = State.DEFAULT;
    @Serialized private final ByteBuffer command = ByteBuffer.allocate(6);
    @Serialized private int response = R0_READY;
    @Serialized private boolean ioMulti;
    @Serialized private int ioAddress;
    @Serialized private int ioCount;
    @Serialized private int ioBlockCount;
    @Serialized private boolean ioWriteError;
    private InputStream inputStream;
    private OutputStream outputStream;

    public SPIBlockDevice(final BlockDevice block) {
        this.block = block;
    }

    public Interrupt getInterrupt() {
        return interrupt;
    }

    @Override
    public Iterable<Interrupt> getInterrupts() {
        return singleton(interrupt);
    }

    @Override
    public void close() throws IOException {
        block.close();
        closeIo();
    }

    @Override
    public int getLength() {
        return 1;
    }

    @Override
    public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
        switch (state) {
            case READING0:
                state = State.READING1;
                break;
            case READING1:
                return read();
            case WRITING0:
                state = State.WRITING1;
                break;
        }

        // We always load before we store in the eLua platform, so we return the response
        // resulting from the last store.
        final int value = response;
        response = R0_READY;
        return value;
    }

    @Override
    public void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException {
        if (state == State.WRITING1) {
            handleWrite((int) value);
        } else {
            if (value == 0xFF) {
                return;
            }

            if (!command.hasRemaining()) {
                state = State.DEFAULT;
                response = R1_ILLEGAL_COMMAND;
                return;
            }

            command.put((byte) value);

            if (!command.hasRemaining()) {
                command.flip();
                handleCommand();
            }
        }
    }

    private int read() {
        // At start, check for out of range requests.
        if (ioCount == 0 && ioBlockCount == 0 && isAddressInvalid(ioAddress)) {
            state = State.DEFAULT;
            return ERROR_OUT_OF_RANGE;
        }

        // We assemble data packets on the fly, so we track the
        // number of bytes written for the current data packet.
        // [data token][   data      ][            crc             ]
        // [     0    ][1-blockLength][blockLength+1..blockLength+2]
        if (ioBlockCount == 0) {
            // data token
            ioBlockCount++;
            return DATA_TOKEN_17_18_24;
        } else if (ioBlockCount == blockLength + 1) {
            // crc (high byte)
            ioBlockCount++;
            return 0; // no crc
        } else if (ioBlockCount == blockLength + 2) {
            // crc (low byte)
            if (ioMulti) {
                // multi block read
                ioBlockCount = 0;
            } else {
                // single block read
                closeIo();
                state = State.DEFAULT;
                response = 0;
            }
            return 0; // no crc
        } else {
            // data
            final InputStream stream = getInputStream();
            byte value;
            try {
                value = (byte) stream.read();
            } catch (final IOException ignored) {
                value = 0;
            }
            ioCount++;
            ioBlockCount++;
            return value & 0xFF;
        }
    }

    private void handleWrite(final int value) {
        // At start, check for out of range requests.
        if (ioCount == 0 && ioBlockCount == 0 && isAddressInvalid(ioAddress)) {
            state = State.DEFAULT;
            response = DATA_RESPONSE_WRITE_ERROR;
            return;
        }

        // We parse data packets on the fly, so we track the
        // number of bytes read for the current data packet.
        // [data token][   data      ][            crc             ]
        // [     0    ][1-blockLength][blockLength+1..blockLength+2]
        if (ioBlockCount == 0) {
            if (value == 0xFF) {
                return;
            }

            // data token
            if (value == STOP_TOKEN_25) {
                // stop token
                state = State.DEFAULT;
            } else if (value != DATA_TOKEN_17_18_24 && value != DATA_TOKEN_25) {
                // invalid data token
                state = State.DEFAULT;
                response = DATA_RESPONSE_WRITE_ERROR;
            } else {
                ioBlockCount++;
            }
        } else if (ioBlockCount == blockLength + 1) {
            // crc, ignored
            ioBlockCount++;
        } else if (ioBlockCount == blockLength + 2) {
            // crc, ignored
            if (ioMulti && !ioWriteError) {
                // multi block write
                ioBlockCount = 0;
            } else {
                // single block write or write error
                closeIo();
                state = State.DEFAULT;
            }
            if (ioWriteError) {
                response = DATA_RESPONSE_WRITE_ERROR;
            } else {
                response = DATA_RESPONSE_ACCEPTED;
            }
        } else {
            // data
            final OutputStream stream = getOutputStream();
            try {
                stream.write(value);
            } catch (final IOException e) {
                ioWriteError = true;
            }
            ioCount++;
            ioBlockCount++;
        }
    }

    private void handleCommand() {
        final int index = command.get() & CMD_MASK;
        final int argument = command.getInt();
        command.clear(); // crc is ignored, reset for next command

        switch (index & CMD_MASK) {
            case CMD0_GO_IDLE_STATE: {
                state = State.DEFAULT;
                response = R1_IDLE;
                break;
            }
            case CMD1_SEND_OP_COND: {
                if (state == State.DEFAULT) {
                    response = 0; // We're ready immediately.
                } else {
                    response = R1_ILLEGAL_COMMAND;
                }
                break;
            }
            case CMD12_STOP_TRANSMISSION: {
                state = State.DEFAULT;
                response = 0;
                break;
            }
            case CMD16_SET_BLOCKLEN: {
                if (argument < 1 || argument > 2048) {
                    response = R1_PARAMETER_ERROR;
                } else {
                    blockLength = argument;
                    response = 0;
                }
                break;
            }
            case CMD17_READ_SINGLE_BLOCK: {
                tryBeginIo(true, argument, false);
                break;
            }
            case CMD18_READ_MULTIPLE_BLOCK: {
                tryBeginIo(true, argument, true);
                break;
            }
            case CMD24_WRITE_SINGLE_BLOCK: {
                tryBeginIo(false, argument, false);
                break;
            }
            case CMD25_WRITE_BLOCK_MULTIPLE: {
                tryBeginIo(false, argument, true);
                break;
            }
            default: {
                response = R1_ILLEGAL_COMMAND;
                break;
            }
        }
    }

    private boolean isAddressInvalid(final int address) {
        return address < 0 || address + blockLength > block.getCapacity();
    }

    private void tryBeginIo(final boolean isRead, final int sector, final boolean multi) {
        closeIo();

        state = isRead ? State.READING0 : State.WRITING0;
        ioAddress = sector;
        ioMulti = multi;
        ioCount = 0;
        ioBlockCount = 0;
        ioWriteError = false;
        response = 0;
    }

    private InputStream getInputStream() {
        if (inputStream == null) {
            inputStream = block.getInputStream(ioAddress + ioCount);
        }
        return inputStream;
    }

    private OutputStream getOutputStream() {
        if (outputStream == null) {
            outputStream = block.getOutputStream(ioAddress + ioCount);
        }
        return outputStream;
    }

    private void closeIo() {
        if (inputStream != null) {
            IOUtils.closeQuietly(inputStream);
            inputStream = null;
        }
        if (outputStream != null) {
            IOUtils.closeQuietly(outputStream);
            outputStream = null;
        }
    }
}
