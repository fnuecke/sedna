package li.cil.sedna.gdbstub;

import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;
import li.cil.sedna.utils.ByteBufferUtils;
import li.cil.sedna.utils.HexUtils;
import li.cil.sedna.utils.Interval;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class GDBStub {
    private enum GDBState {
        DISCONNECTED,
        WAITING_FOR_COMMAND,
        STOP_REPLY
    }

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_PACKET_SIZE = 0x2000;
    private final ServerSocketChannel listeningSock;
    private final CPUDebugInterface cpu;
    private final byte[] targetDescription;
    private GDBState state = GDBState.DISCONNECTED;
    private InputStream input;
    private OutputStream output;
    private SocketChannel sock;

    public GDBStub(final ServerSocketChannel socket, final CPUDebugInterface cpu) {
        this.listeningSock = socket;
        this.cpu = cpu;
        this.cpu.addBreakpointListener(this::handleBreakpointHit);
        this.cpu.addWatchpointListener(this::handleWatchpointHit);
        this.targetDescription = loadTargetDescription();
    }

    private static byte[] loadTargetDescription() {
        try (final InputStream stream = GDBStub.class.getResourceAsStream("/gdb/target.xml");
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (stream == null) {
                throw new RuntimeException("Target description not found");
            }
            stream.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static GDBStub createDefault(final CPUDebugInterface cpu, final int port) throws IOException {
        final ServerSocketChannel chan = ServerSocketChannel.open();
        chan.configureBlocking(false);
        chan.bind(new InetSocketAddress(port));
        return new GDBStub(chan, cpu);
    }

    public void run(final boolean waitForMessage) {
        if (isMessageAvailable() || waitForMessage) {
            runLoop(new MessageStop());
        }
    }

    private static abstract class StopReason {
        public abstract void sendStopReply(Writer w) throws IOException;
    }

    private static class MessageStop extends StopReason {
        @Override
        public void sendStopReply(Writer w) throws IOException {
            w.write("S05");
        }
    }

    private static class BreakpointStop extends StopReason {
        @Override
        public void sendStopReply(Writer w) throws IOException {
            w.write("S05");
        }
    }

    private static class WatchpointStop extends StopReason {
        private final long address;
        public WatchpointStop(long address) {
            this.address = address;
        }
        @Override
        public void sendStopReply(Writer w) throws IOException {
            w.write("T05watch:");
            HexUtils.put64BE(w, address);
            w.write(';');
        }
    }

    private void runLoop(StopReason reason) {
        final ByteBuffer packetBuffer = ByteBuffer.allocate(8192);
        loop:
        while (true) {
            switch (state) {
                case DISCONNECTED -> {
                    // If we get disconnected while stopped, the CPU should stay stopped. Nothing to do
                    // but wait for a new connection
                    // If we end up needing to wait on multiple things, we can use epoll (or platform equivalent)
                    // via the Selector API
                    try {
                        this.listeningSock.configureBlocking(true);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                    this.tryConnect();
                }
                case STOP_REPLY -> {
                    try (final var s = new GDBPacketOutputStream(output);
                         final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                        reason.sendStopReply(w);
                        state = GDBState.WAITING_FOR_COMMAND;
                    } catch (final IOException e) {
                        disconnect();
                    }
                }
                case WAITING_FOR_COMMAND -> {
                    try {
                        packetBuffer.clear();
                        if (!receivePacket(packetBuffer)) {
                            disconnect();
                            break;
                        }
                        if (packetBuffer.limit() == 0) continue;
                        LOGGER.debug("Packet: {}\n", asciiBytesToEscaped(packetBuffer.slice()));

                        final byte command = packetBuffer.get();
                        switch (command) {
                            case '?' -> {
                                try (final var s = new GDBPacketOutputStream(output);
                                     final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("S05");
                                }
                            }
                            //General Query
                            case 'q' -> handleQuery(packetBuffer);
                            case 'g' -> readGeneralRegisters();
                            case 'G' -> writeGeneralRegisters(packetBuffer);
                            case 'm' -> handleReadMemory(packetBuffer);
                            case 'M' -> handleWriteMemory(packetBuffer);
                            case 'Z' -> {
                                final byte type = packetBuffer.get();
                                switch (type) {
                                    case '0', '1' -> handleBreakpointAdd(packetBuffer);
                                    case '2', '3', '4' -> handleWatchpointAdd(type, packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'z' -> {
                                final byte type = packetBuffer.get();
                                switch (type) {
                                    case '0', '1' -> handleBreakpointRemove(packetBuffer);
                                    case '2', '3', '4' -> handleWatchpointRemove(type, packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'c' -> {
                                state = GDBState.STOP_REPLY;
                                break loop;
                            }
                            case 's' -> {
                                // We don't support the optional 'addr' parameter of the 's' packet.
                                // It appears that GDB doesn't (and never has) sent this parameter anyway.
                                if (packetBuffer.hasRemaining()) {
                                    unknownCommand(packetBuffer);
                                    return;
                                }
                                reason = handleStep();
                            }
                            case 'D' -> {
                                try (final var s = new GDBPacketOutputStream(output);
                                     final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("OK");
                                }
                                disconnect();
                                break loop;
                            }
                            case 'p' -> handleReadRegister(packetBuffer);
                            case 'P' -> handleWriteRegister(packetBuffer);
                            default -> unknownCommand(packetBuffer);
                        }
                    } catch (final IOException e) {
                        disconnect();
                    }
                }
            }
        }
    }

    private boolean tryConnect() {
        try {
            final SocketChannel sock = listeningSock.accept();
            if (sock == null) return false;
            this.sock = sock;
        } catch (final IOException e) {
            return false;
        }
        try {
            this.input = new BufferedInputStream(sock.socket().getInputStream());
            this.output = new BufferedOutputStream(sock.socket().getOutputStream());
            state = GDBState.WAITING_FOR_COMMAND;
            return true;
        } catch (final IOException e) {
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        try {
            LOGGER.info("GDB disconnected");
            this.state = GDBState.DISCONNECTED;
            this.sock.close();
        } catch (final IOException ignored) {
        } finally {
            this.input = null;
            this.output = null;
            this.sock = null;
        }
    }

    private boolean isMessageAvailable() {
        return switch (state) {
            case DISCONNECTED -> tryConnect();
            case WAITING_FOR_COMMAND, STOP_REPLY -> {
                try {
                    yield this.input.available() > 0;
                } catch (final IOException e) {
                    disconnect();
                    yield false;
                }
            }
        };
    }

    /**
     * While most packets are 7bit ascii, a few are binary, so we'll use a ByteBuffer.
     */
    private boolean receivePacket(final ByteBuffer buffer) {
        while (true) {
            try {
                byte actualChecksum = 0;
                while (true) {
                    final int c = input.read();

                    if (c == '$') break;
                    if (c == -1) return false;
                }
                while (true) {
                    final int c = input.read();
                    if (c == '#') break;
                    if (c == -1) return false;
                    buffer.put((byte) c);
                    actualChecksum += (byte) c;
                }
                byte expectedChecksum;
                int c;
                byte d;
                if ((c = input.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum = (byte) (d << 4);
                if ((c = input.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum |= d;

                if (actualChecksum != expectedChecksum) {
                    output.write('-');
                    output.flush();
                    continue;
                }
                output.write('+');
                output.flush();
                buffer.flip();
                return true;
            } catch (final IOException e) {
                return false;
            }
        }
    }

    private void handleBreakpointHit(final long address) {
        runLoop(new BreakpointStop());
    }

    private void handleWatchpointHit(final long address) {
        runLoop(new WatchpointStop(address));
    }

    private void handleSupported(final ByteBuffer packet) throws IOException {
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            w.write("PacketSize=%x;qXfer:features:read+".formatted(MAX_PACKET_SIZE));
        }
    }

    private void handleReadMemory(final ByteBuffer buffer) throws IOException {
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressEnd = command.indexOf(',');
        final long address = Long.parseUnsignedLong(command, 0, addressEnd, 16);
        final int length = Integer.parseInt(command, addressEnd + 1, command.length(), 16);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            try {
                final byte[] mem = cpu.loadDebug(address, length);
                HexFormat.of().formatHex(w, mem);
            } catch (final R5MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleWriteMemory(final ByteBuffer buffer) throws IOException {
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressEnd = command.indexOf(',');
        final int lengthEnd = command.indexOf(':', addressEnd + 1);
        final long address = Long.parseUnsignedLong(command, 0, addressEnd, 16);
        final int length = Integer.parseInt(command, addressEnd + 1, lengthEnd, 16);
        final int actualLength = (command.length() - (lengthEnd + 1)) / 2;
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            if (length != actualLength) {
                w.write("E22");
                return;
            }
            final byte[] mem = HexFormat.of().parseHex(command, lengthEnd + 1, command.length());
            try {
                final int wrote = cpu.storeDebug(address, mem);
                if (wrote < length) {
                    w.write("E14");
                } else {
                    w.write("OK");
                }
            } catch (final R5MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleBreakpointAdd(final ByteBuffer buffer) throws IOException {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getVarLengthInt(chars);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            cpu.addBreakpoint(address);
            w.write("OK");
        }
    }

    private void handleBreakpointRemove(final ByteBuffer buffer) throws IOException {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getVarLengthInt(chars);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            cpu.removeBreakpoint(address);
            w.write("OK");
        }
    }

    private void handleWatchpointAdd(final byte type, final ByteBuffer buffer) throws IOException {
        buffer.get();
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressCharsEnd = command.indexOf(',');
        final long address = Long.parseUnsignedLong(command, 0, addressCharsEnd, 16);
        final int length = Integer.parseInt(command, addressCharsEnd + 1, command.length(), 16);
        final Interval interval = new Interval(address, length);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            Watchpoint watchpoint =  switch (type) {
                case '2' -> new Watchpoint(interval, false, true);
                case '3' -> new Watchpoint(interval, true, false);
                case '4' -> new Watchpoint(interval, true, true);
                default -> throw new IllegalStateException("Unexpected value: " + type);
            };
            cpu.addWatchpoint(watchpoint);
            w.write("OK");
        }
    }

    private void handleWatchpointRemove(final byte type, final ByteBuffer buffer) throws IOException {
        buffer.get();
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int addressCharsEnd = command.indexOf(',');
        final long address = Long.parseUnsignedLong(command, 0, addressCharsEnd, 16);
        final int length = Integer.parseInt(command, addressCharsEnd + 1, command.length(), 16);
        final Interval interval = new Interval(address, length);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            Watchpoint watchpoint =  switch (type) {
                case '2' -> new Watchpoint(interval, false, true);
                case '3' -> new Watchpoint(interval, true, false);
                case '4' -> new Watchpoint(interval, true, true);
                default -> throw new IllegalStateException("Unexpected value: " + type);
            };
            cpu.removeWatchpoint(watchpoint);
            w.write("OK");
        }
    }

    private MessageStop handleStep() {
        cpu.step();
        state = GDBState.STOP_REPLY;
        return new MessageStop();
    }

    private void handleQuery(ByteBuffer packetBuffer) throws IOException {
        final byte[] Supported = "Supported:".getBytes(StandardCharsets.US_ASCII);
        final byte[] Attached = "Attached".getBytes(StandardCharsets.US_ASCII);
        final byte[] features = "Xfer:features:read:".getBytes(StandardCharsets.US_ASCII);
        if (ByteBufferUtils.startsWith(packetBuffer, ByteBuffer.wrap(Supported))) {
            packetBuffer.position(packetBuffer.position() + Supported.length);
            handleSupported(packetBuffer);
        } else if (ByteBufferUtils.startsWith(packetBuffer, ByteBuffer.wrap(Attached))) {
            try (final var s = new GDBPacketOutputStream(output);
                 final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                w.write("1");
            }
        } else if (ByteBufferUtils.startsWith(packetBuffer, ByteBuffer.wrap(features))) {
            packetBuffer.position(packetBuffer.position() + features.length);
            handleReadTargetDescription(packetBuffer);
        } else {
            unknownCommand(packetBuffer);
        }
    }

    private void handleReadTargetDescription(ByteBuffer buf) throws IOException {
        try (final var s = new GDBPacketOutputStream(output)) {
            try {
                String annex = ByteBufferUtils.getStringToken(buf, (byte) ':');
                int offset = Integer.parseInt(ByteBufferUtils.getStringToken(buf, (byte) ','), 16);
                int length = Integer.parseInt(ByteBufferUtils.tokenAsString(buf), 16);
                handleReadTargetDescription(annex, offset, length, s);
            } catch (ByteBufferUtils.TokenException e) {
                LOGGER.error("Failed to parse qXfer features read packet", e);
                s.write("E00".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private void handleReadTargetDescription(String annex, int offset, int length, OutputStream out) throws IOException {
        if(!annex.equals("target.xml")) {
            out.write("E00".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if(offset > targetDescription.length || offset < 0) {
            out.write("E00".getBytes(StandardCharsets.US_ASCII));
            return;
        } else if (offset == targetDescription.length) {
            out.write('l');
            return;
        }
        // We need to make sure we don't exceed the max packet size
        // Due to escaping each byte may take up to 2 bytes, hence the divide by 2.
        // The 5 comes from 1 '$', 2 checksum bytes, 1 '#', and one 'l' for the qXfer read response
        final int maxChunkLength = (MAX_PACKET_SIZE / 2) - 5;
        final int maxLength = Math.min(targetDescription.length - offset, maxChunkLength);
        length = Math.min(length, maxLength);
        if(offset + length == targetDescription.length) {
            out.write('l');
        } else {
            out.write('m');
        }

        try(GDBBinaryOutputStream binOut = new GDBBinaryOutputStream(out)) {
            binOut.write(targetDescription, offset, length);
        }
    }

    private String asciiBytesToEscaped(final ByteBuffer bytes) {
        final StringBuilder sb = new StringBuilder(bytes.remaining());
        while (bytes.hasRemaining()) {
            final byte b = bytes.get();
            //Printable ASCII
            if (b >= 0x20 && b <= 0x7e) {
                sb.append((char) b);
            } else {
                sb.append("\\x");
                HexFormat.of().toHexDigits(sb, b);
            }
        }
        return sb.toString();
    }

    private void unknownCommand(final ByteBuffer packet) throws IOException {
        LOGGER.debug("Unknown command: {}\n", asciiBytesToEscaped(packet.position(0)));
        // Send an empty packet
        new GDBPacketOutputStream(output).close();
    }

    private void readGeneralRegisters() throws IOException {
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            for (final long l : cpu.getGeneralRegisters()) {
                HexUtils.put64(w, l);
            }
            HexUtils.put64(w, cpu.getProgramCounter());
        }
    }

    private void writeGeneralRegisters(final ByteBuffer buf) throws IOException {
        final String regs = StandardCharsets.US_ASCII.decode(buf).toString();
        final ByteBuffer regsRaw = ByteBuffer.wrap(HexFormat.of().parseHex(regs)).order(ByteOrder.LITTLE_ENDIAN);
        final long[] xr = cpu.getGeneralRegisters();
        for (int i = 0; i < xr.length; i++) {
            xr[i] = regsRaw.getLong();
        }
        cpu.setProgramCounter(regsRaw.getLong());
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            w.write("OK");
        }
    }

    // Must be kept in sync with target.xml
    private static final int regNumFirstX = 0;
    private static final int regNumLastX = 31;
    private static final int regNumPc = 32;
    private static final int regNumFirstF = 33;
    private static final int regNumLastF = 64;
    private static final int regNumFflags = 65;
    private static final int regNumFrm = 66;
    private static final int regNumFcsr = 67;
    private static final int regNumPriv = 68;
    private static final int regNumFirstCSR = 0x1000;
    private static final int regNumSwitch32 = 0x1bc0;
    private static final int regNumLastCSR = 0x1fff;

    private void handleReadRegister(final ByteBuffer buffer) throws IOException {
        final String regNumStr = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int regNum = Integer.parseInt(regNumStr, 16);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            if(regNum >= regNumFirstX && regNum <= regNumLastX) HexUtils.put64(w, cpu.getGeneralRegisters()[regNum - regNumFirstX]);
            else if(regNum == regNumPc) HexUtils.put64(w, cpu.getProgramCounter());
            else if(regNum >= regNumFirstF && regNum <= regNumLastF) HexUtils.put64(w, cpu.getFloatingRegisters()[regNum - regNumFirstF]);
            else if(regNum == regNumFflags) HexUtils.put32(w, cpu.getFflags());
            else if(regNum == regNumFrm) HexUtils.put32(w, cpu.getFrm());
            else if(regNum == regNumFcsr) HexUtils.put32(w, cpu.getFcsr());
            else if(regNum == regNumPriv) HexUtils.put64(w, cpu.getPriv());
            else if(regNum >= regNumFirstCSR && regNum <= regNumLastCSR) {
                if(regNum == regNumSwitch32) {
                    // This is a write-only register, which GDB doesn't understand. We're
                    // special casing it so GDB (which always does a read before it writes) can
                    // write to it
                    HexUtils.put64(w, 0);
                    return;
                }
                try {
                    short csr = (short) (regNum - 0x1000);
                    HexUtils.put64(w, cpu.getCSR(csr));
                } catch (R5IllegalInstructionException e) {
                    w.write("E01");
                }
            } else {
                w.write("E01");
            }
        }
    }

    private void handleWriteRegister(final ByteBuffer buffer) throws IOException {
        final String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        final String[] commandArr = command.split("=");
        final String regNumStr = commandArr[0];
        final int regNum = Integer.parseInt(regNumStr, 16);
        final String regValStr = commandArr[1];
        final ByteBuffer regValRaw = ByteBuffer.wrap(HexFormat.of().parseHex(regValStr)).order(ByteOrder.LITTLE_ENDIAN);
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            if(regNum >= regNumFirstX && regNum <= regNumLastX) cpu.getGeneralRegisters()[regNum - regNumFirstX] = regValRaw.getLong();
            else if(regNum == regNumPc) cpu.setProgramCounter(regValRaw.getLong());
            else if(regNum >= regNumFirstF && regNum <= regNumLastF) cpu.getFloatingRegisters()[regNum - regNumFirstF] = regValRaw.getLong();
            else if(regNum == regNumFflags) cpu.setFflags((byte) regValRaw.getInt());
            else if(regNum == regNumFrm) cpu.setFrm((byte) regValRaw.getInt());
            else if(regNum == regNumFcsr) cpu.setFcsr(regValRaw.getInt());
            else if(regNum == regNumPriv) cpu.setPriv((byte) regValRaw.getInt());
            else if(regNum >= regNumFirstCSR && regNum <= regNumLastCSR) {
                try {
                    short csr = (short) (regNum - 0x1000);
                    cpu.setCSR(csr, regValRaw.getLong());
                } catch (R5IllegalInstructionException e) {
                    w.write("E01");
                    return;
                }
            } else {
                w.write("E01");
                return;
            }
            w.write("OK");
        }
    }
}
