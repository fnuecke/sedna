package li.cil.sedna.gdbstub;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.utils.ByteBufferUtils;
import li.cil.sedna.utils.HexUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class GDBStub {
    private enum State {
        DISCONNECTED,
        RUNNING,
        STOPPED,
    }

    private static final String SIGNAL_INT = "S02";
    private static final String SIGNAL_TRAP = "S05";

    private static final byte INTERRUPT_REQUEST = 0x03; // What GDB sends for Ctrl+C.

    private static final int RECEIVE_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_PACKET_SIZE = 0x2000;

    private static final String FEATURES_READ = "Xfer:features:read:";

    private static final Logger LOGGER = LogManager.getLogger(GDBStub.class);

    private final ServerSocketChannel listeningSock;
    private final CPUDebugInterface cpu;

    private State state = State.DISCONNECTED;
    private SocketChannel sock;
    private InputStream input;
    private OutputStream output;

    private final ByteBuffer rx = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

    private final LongSet breakpoints = new LongOpenHashSet();

    private String pendingStopSignal;
    private boolean waitingForAttach;

    public GDBStub(final ServerSocketChannel socket, final CPUDebugInterface cpu) {
        this.listeningSock = socket;
        this.cpu = cpu;
        this.cpu.addBreakpointListener(this::handleBreakpointHit);
    }

    public static GDBStub createDefault(final CPUDebugInterface cpu, final int port) throws IOException {
        final ServerSocketChannel chan = ServerSocketChannel.open();
        chan.configureBlocking(false);
        chan.bind(new InetSocketAddress(port));
        return new GDBStub(chan, cpu);
    }

    public void waitForAttach() {
        waitingForAttach = true;
        state = State.STOPPED;
    }

    public boolean isHalted() {
        return state == State.STOPPED;
    }

    public void poll() {
        if (state == State.DISCONNECTED || sock == null) {
            if (!tryConnect()) {
                return;
            }
        }

        receive();

        if (pendingStopSignal != null && output != null) {
            final String signal = pendingStopSignal;
            pendingStopSignal = null;
            sendPacket(signal);
        }
    }

    // ------------------------------------------------------------- //
    // Connection

    private boolean tryConnect() {
        final SocketChannel sock;
        try {
            sock = listeningSock.accept();
        } catch (final IOException e) {
            return false;
        }
        if (sock == null) {
            return false;
        }

        this.sock = sock;
        try {
            this.input = new BufferedInputStream(sock.socket().getInputStream());
            this.output = new BufferedOutputStream(sock.socket().getOutputStream());
        } catch (final IOException e) {
            disconnect();
            return false;
        }

        LOGGER.info("GDB connected");
        rx.clear();
        waitingForAttach = false;

        stop(SIGNAL_TRAP);
        return true;
    }

    private void disconnect() {
        LOGGER.info("GDB disconnected");

        if (sock != null) {
            try {
                sock.close();
            } catch (final IOException ignored) {
            }
        }

        sock = null;
        input = null;
        output = null;
        pendingStopSignal = null;
        rx.clear();

        for (final long address : breakpoints) {
            cpu.removeBreakpoint(address);
        }
        breakpoints.clear();

        state = waitingForAttach ? State.STOPPED : State.DISCONNECTED;
    }

    // ------------------------------------------------------------- //
    // Receiving

    private void receive() {
        if (!fill()) {
            return;
        }

        rx.flip();
        try {
            while (sock != null && handleOnePacket()) {
                // Keep going while complete packets remain.
            }
        } catch (final IOException e) {
            disconnect();
        } finally {
            if (sock != null) {
                rx.compact();
            }
        }
    }

    private boolean fill() {
        try {
            int available;
            while ((available = input.available()) > 0 && rx.hasRemaining()) {
                final int count = Math.min(available, rx.remaining());
                final byte[] chunk = new byte[count];
                final int read = input.read(chunk, 0, count);
                if (read < 0) {
                    disconnect();
                    return false;
                }
                rx.put(chunk, 0, read);
            }
            return true;
        } catch (final IOException e) {
            disconnect();
            return false;
        }
    }

    private boolean handleOnePacket() throws IOException {
        // Find a packet start. Acknowledgements are ignored -- we never retransmit -- and a lone
        // 0x03 is GDB asking us to interrupt the machine.
        int start = -1;
        while (rx.hasRemaining()) {
            final int position = rx.position();
            final byte b = rx.get();
            if (b == '$') {
                start = position + 1;
                break;
            }
            if (b == INTERRUPT_REQUEST) {
                stop(SIGNAL_INT);
            }
        }
        if (start < 0) {
            return false;
        }

        int hash = -1;
        for (int i = start; i < rx.limit(); i++) {
            if (rx.get(i) == '#') {
                hash = i;
                break;
            }
        }
        // The two checksum digits have to have arrived too before this is a packet.
        if (hash < 0 || hash + 2 >= rx.limit()) {
            rx.position(start - 1); // Rewind to the '$' and wait for the rest.
            return false;
        }

        byte checksum = 0;
        for (int i = start; i < hash; i++) {
            checksum += rx.get(i);
        }
        final int expected = (HexFormat.fromHexDigit(rx.get(hash + 1)) << 4)
                | HexFormat.fromHexDigit(rx.get(hash + 2));

        final ByteBuffer packet = rx.slice(start, hash - start);
        rx.position(hash + 3);

        if ((checksum & 0xFF) != expected) {
            output.write('-');
            output.flush();
            return true;
        }
        output.write('+');
        output.flush();

        LOGGER.debug("Packet: {}", asciiBytesToEscaped(packet.slice()));
        handleCommand(packet);
        return true;
    }

    private void handleCommand(final ByteBuffer packetBuffer) throws IOException {
        if (!packetBuffer.hasRemaining()) {
            return;
        }

        final byte command = packetBuffer.get();
        switch (command) {
            case '?' -> sendPacket(SIGNAL_TRAP);
            //General Query
            case 'q' -> handleQuery(packetBuffer);
            case 'g' -> readGeneralRegisters();
            case 'G' -> writeGeneralRegisters(packetBuffer);
            case 'p' -> handleReadRegister(packetBuffer);
            case 'P' -> handleWriteRegister(packetBuffer);
            case 'm' -> handleReadMemory(packetBuffer);
            case 'M' -> handleWriteMemory(packetBuffer);
            case 'Z' -> {
                final byte type = packetBuffer.get();
                switch (type) {
                    case '0', '1' -> handleBreakpointAdd(packetBuffer);
                    default -> unknownCommand(packetBuffer);
                }
            }
            case 'z' -> {
                final byte type = packetBuffer.get();
                switch (type) {
                    case '0', '1' -> handleBreakpointRemove(packetBuffer);
                    default -> unknownCommand(packetBuffer);
                }
            }
            case 'c' -> state = State.RUNNING;
            case 's' -> {
                // We don't support the optional 'addr' parameter of the 's' packet.
                // It appears that GDB doesn't (and never has) sent this parameter anyway.
                if (packetBuffer.hasRemaining()) {
                    unknownCommand(packetBuffer);
                } else {
                    // Safe to run the CPU from here: poll() is called by whoever steps the machine,
                    // not from inside the interpreter.
                    cpu.step();
                    stop(SIGNAL_TRAP);
                }
            }
            case 'D' -> {
                sendPacket("OK");
                disconnect();
            }
            default -> unknownCommand(packetBuffer);
        }
    }

    // ------------------------------------------------------------- //
    // Stopping

    private void handleBreakpointHit(final long address) {
        stop(SIGNAL_TRAP);
    }

    private void stop(final String signal) {
        state = State.STOPPED;
        pendingStopSignal = signal;
    }

    // ------------------------------------------------------------- //
    // Commands

    private void sendPacket(final String contents) {
        if (output == null) {
            return;
        }
        try (final var s = new GDBPacketOutputStream(output);
             final var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            w.write(contents);
        } catch (final IOException e) {
            disconnect();
        }
    }

    private void handleQuery(final ByteBuffer buffer) throws IOException {
        if (startsWith(buffer, "Supported:")) {
            sendPacket("PacketSize=%x;qXfer:features:read+".formatted(MAX_PACKET_SIZE));
        } else if (startsWith(buffer, "Attached")) {
            sendPacket("1");
        } else if (startsWith(buffer, FEATURES_READ)) {
            buffer.position(buffer.position() + FEATURES_READ.length());
            handleReadTargetDescription(buffer);
        } else {
            unknownCommand(buffer);
        }
    }

    private void handleReadTargetDescription(final ByteBuffer buffer) throws IOException {
        try {
            String annex = ByteBufferUtils.getStringToken(buffer, (byte) ':');
            int offset = Integer.parseInt(ByteBufferUtils.getStringToken(buffer, (byte) ','), 16);
            int length = Integer.parseInt(ByteBufferUtils.tokenAsString(buffer), 16);

            handleReadTargetDescription(annex, offset, length);
        } catch (ByteBufferUtils.TokenException e) {
            LOGGER.error("Failed to parse qXfer features read packet", e);
            sendPacket("E00");
        }
    }

    private void handleReadTargetDescription(String annex, int offset, int length) throws IOException {
        final byte[] description = cpu.getTargetDescription();
        if (!annex.equals("target.xml") || description == null) {
            sendPacket("E00");
            return;
        }

        if (offset > description.length || offset < 0) {
            sendPacket("E00");
            return;
        } else if (offset == description.length) {
            sendPacket("l");
            return;
        }

        try (final var s = new GDBPacketOutputStream(output)) {
            // We need to make sure we don't exceed the max packet size
            // Due to escaping each byte may take up to 2 bytes, hence the divide by 2.
            // The 5 comes from 1 '$', 2 checksum bytes, 1 '#', and one 'l' for the qXfer read response
            final int maxChunkLength = (MAX_PACKET_SIZE / 2) - 5;
            final int maxLength = Math.min(description.length - offset, maxChunkLength);
            length = Math.min(length, maxLength);
            if (offset + length == description.length) {
                s.write('l');
            } else {
                s.write('m');
            }

            try (GDBBinaryOutputStream binOut = new GDBBinaryOutputStream(s)) {
                binOut.write(description, offset, length);
            }
        }
    }

    private void handleReadRegister(final ByteBuffer buffer) throws IOException {
        final String request = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int id;
        try {
            id = Integer.parseInt(request, 16);
        } catch (final NumberFormatException e) {
            sendPacket("E01");
            return;
        }

        final int size = cpu.getRegisterSize(id);
        if (size == 0) {
            sendPacket("E01");
            return;
        }

        try (final var s = new GDBPacketOutputStream(output);
             final var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            HexUtils.putRegister(w, cpu.getRegister(id), size);
        }
    }

    private void handleWriteRegister(final ByteBuffer buffer) {
        final String request = StandardCharsets.US_ASCII.decode(buffer).toString();
        final int valueStart = request.indexOf('=');
        if (valueStart < 0) {
            sendPacket("E01");
            return;
        }

        final int id;
        final long value;
        try {
            id = Integer.parseInt(request, 0, valueStart, 16);
            value = parseRegister(request.substring(valueStart + 1));
        } catch (final IllegalArgumentException e) { // Covers HexFormat's malformed-input errors.
            sendPacket("E01");
            return;
        }

        sendPacket(cpu.setRegister(id, value) ? "OK" : "E01");
    }

    private static long parseRegister(final String hex) {
        final byte[] raw = HexFormat.of().parseHex(hex);
        long value = 0;
        for (int i = 0; i < raw.length && i < Long.BYTES; i++) {
            value |= (raw[i] & 0xFFL) << (i * 8);
        }
        return value;
    }

    private static boolean startsWith(final ByteBuffer buffer, final String prefix) {
        return ByteBufferUtils.startsWith(buffer, ByteBuffer.wrap(prefix.getBytes(StandardCharsets.US_ASCII)));
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
            } catch (final MemoryAccessException e) {
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
            } catch (final MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleBreakpointAdd(final ByteBuffer buffer) {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getVarLengthInt(chars);
        cpu.addBreakpoint(address);
        breakpoints.add(address);
        sendPacket("OK");
    }

    private void handleBreakpointRemove(final ByteBuffer buffer) {
        buffer.get();
        final var chars = StandardCharsets.US_ASCII.decode(buffer);
        final long address = HexUtils.getVarLengthInt(chars);
        cpu.removeBreakpoint(address);
        breakpoints.remove(address);
        sendPacket("OK");
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

    private void writeGeneralRegisters(final ByteBuffer buf) {
        final String regs = StandardCharsets.US_ASCII.decode(buf).toString();
        final ByteBuffer regsRaw = ByteBuffer.wrap(HexFormat.of().parseHex(regs)).order(ByteOrder.LITTLE_ENDIAN);
        final long[] xr = cpu.getGeneralRegisters();
        for (int i = 0; i < xr.length; i++) {
            xr[i] = regsRaw.getLong();
        }
        cpu.setProgramCounter(regsRaw.getLong());
        sendPacket("OK");
    }

    private void unknownCommand(final ByteBuffer packet) throws IOException {
        LOGGER.debug("Unknown command: {}", asciiBytesToEscaped(packet.position(0)));
        // Send an empty packet
        new GDBPacketOutputStream(output).close();
    }

    private static String asciiBytesToEscaped(final ByteBuffer bytes) {
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
}
