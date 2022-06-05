package li.cil.sedna.gdbstub;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.riscv.R5CPUDebug;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;
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

public class GDBStub {

    private enum GDBState {
        DISCONNECTED,
        WAITING_FOR_COMMAND,
        STOP_REPLY
    }

    public enum StopReason {
        MESSAGE,
        BREAKPOINT
    }

    private static final Logger LOGGER = LogManager.getLogger();

    private GDBState state = GDBState.DISCONNECTED;
    private InputStream gdbIn;
    private OutputStream gdbOut;

    private final ServerSocketChannel listeningSock;
    private SocketChannel sock;

    private final R5CPUDebug cpu;

    public GDBStub(ServerSocketChannel socket, R5CPUDebug cpu) {
        this.listeningSock = socket;
        this.cpu = cpu;
    }

    public static GDBStub defaultGDBStub(R5CPUDebug cpu, int port) throws IOException {
        ServerSocketChannel chan = ServerSocketChannel.open();
        chan.configureBlocking(false);
        chan.bind(new InetSocketAddress(port));
        return new GDBStub(chan, cpu);
    }

    private boolean attemptConnect() {
        try {
            SocketChannel sock = listeningSock.accept();
            if (sock == null) return false;
            this.sock = sock;
        } catch (IOException e) {
            return false;
        }
        try {
            this.gdbIn = new BufferedInputStream(sock.socket().getInputStream());
            this.gdbOut = new BufferedOutputStream(sock.socket().getOutputStream());
            state = GDBState.WAITING_FOR_COMMAND;
            return true;
        } catch (IOException e) {
            disconnect();
            return false;
        }
    }

    public boolean messagesAvailable() {
        return switch (state) {
            case DISCONNECTED -> attemptConnect();
            case WAITING_FOR_COMMAND, STOP_REPLY -> {
                try {
                    yield this.gdbIn.available() > 0;
                } catch (IOException e) {
                    disconnect();
                    yield false;
                }
            }
        };
    }

    //While most packets are 7bit ascii, a few are binary, so we'll use a ByteBuffer
    private boolean receivePacket(ByteBuffer buffer) {
        while (true) {
            try {
                byte actualChecksum = 0;
                while (true) {
                    int c = gdbIn.read();

                    if (c == '$') break;
                    if (c == -1) return false;
                }
                while (true) {
                    int c = gdbIn.read();
                    if (c == '#') break;
                    if (c == -1) return false;
                    buffer.put((byte) c);
                    actualChecksum += (byte) c;
                }
                byte expectedChecksum;
                int c;
                byte d;
                if ((c = gdbIn.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum = (byte) (d << 4);
                if ((c = gdbIn.read()) == -1 || (d = (byte) HexFormat.fromHexDigit(c)) == -1) return false;
                expectedChecksum |= d;

                if (actualChecksum != expectedChecksum) {
                    gdbOut.write('-');
                    gdbOut.flush();
                    continue;
                }
                gdbOut.write('+');
                gdbOut.flush();
                buffer.flip();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void disconnect() {
        try {
            LOGGER.info("GDB disconnected");
            this.state = GDBState.DISCONNECTED;
            this.sock.close();
        } catch (IOException ignored) {
        } finally {
            this.gdbIn = null;
            this.gdbOut = null;
            this.sock = null;
        }
    }

    private void handleSupported(ByteBuffer buffer) throws IOException {
        try (var s = new PacketOutputStream(gdbOut);
             var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            // Size in hex
            w.write("PacketSize=2000");
        }
    }

    private void unknownCommand(ByteBuffer packet) throws IOException {
        LOGGER.debug("Unknown command: {}\n", StandardCharsets.US_ASCII.decode(packet.position(0)));
        // Send an empty packet
        try (var ignored = new PacketOutputStream(gdbOut)) {
        }
    }

    private void readGeneralRegisters() throws IOException {
        try (var s = new PacketOutputStream(gdbOut);
             var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            for (long l : cpu.getX()) {
                HexUtils.fromLong(w, l);
            }
            HexUtils.fromLong(w, cpu.getPc());
        }
    }

    private void writeGeneralRegisters(ByteBuffer buf) throws IOException {
        String regs = StandardCharsets.US_ASCII.decode(buf).toString();
        ByteBuffer regsRaw = ByteBuffer.wrap(HexFormat.of().parseHex(regs)).order(ByteOrder.LITTLE_ENDIAN);
        long[] xr = cpu.getX();
        for (int i = 0; i < xr.length; i++) {
            xr[i] = regsRaw.getLong();
        }
        cpu.setPc(regsRaw.getLong());
        try (var s = new PacketOutputStream(gdbOut);
             var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            w.write("OK");
        }
    }

    private void handleReadMemory(ByteBuffer buffer) throws IOException {
        String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        int addrEnd = command.indexOf(',');
        long addr = Long.parseUnsignedLong(command, 0, addrEnd, 16);
        int length = Integer.parseInt(command, addrEnd + 1, command.length(), 16);
        try (var s = new PacketOutputStream(gdbOut);
             var w = new BufferedWriter(new OutputStreamWriter(s, StandardCharsets.US_ASCII))) {
            try {
                byte[] mem = cpu.loadDebug(addr, length);
                HexFormat.of().formatHex(w, mem);
            } catch (R5MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleWriteMemory(ByteBuffer buffer) throws IOException {
        String command = StandardCharsets.US_ASCII.decode(buffer).toString();
        int addrEnd = command.indexOf(',');
        int lengthEnd = command.indexOf(':', addrEnd + 1);
        long addr = Long.parseUnsignedLong(command, 0, addrEnd, 16);
        int length = Integer.parseInt(command, addrEnd + 1, lengthEnd, 16);
        int actualLength = (command.length() - (lengthEnd + 1)) / 2;
        try (var s = new PacketOutputStream(gdbOut);
             var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            if (length != actualLength) {
                w.write("E22");
                return;
            }
            byte[] mem = HexFormat.of().parseHex(command, lengthEnd + 1, command.length());
            try {
                int wrote = cpu.storeDebug(addr, mem);
                if (wrote < length) {
                    w.write("E14");
                } else {
                    w.write("OK");
                }
            } catch (R5MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    public void breakpointHit(final long address) {
        gdbloop(StopReason.BREAKPOINT);
    }

    private void handleBreakpointAdd(ByteBuffer buffer) throws IOException {
        buffer.get();
        var charbuf = StandardCharsets.US_ASCII.decode(buffer);
        long addr = HexUtils.toLong(charbuf);
        try (var s = new PacketOutputStream(gdbOut);
             var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            try {
                cpu.addBreakpoint(addr);
                w.write("OK");
            } catch (R5MemoryAccessException e) {
                w.write("E14");
            }
        }
    }

    private void handleBreakpointRemove(ByteBuffer buffer) throws IOException {
        buffer.get();
        var charbuf = StandardCharsets.US_ASCII.decode(buffer);
        long addr = HexUtils.toLong(charbuf);
        try (var s = new PacketOutputStream(gdbOut);
             var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
            cpu.removeBreakpoint(addr);
            w.write("OK");
        }
    }

    private String asciiBytesToEscaped(ByteBuffer bytes) {
        StringBuilder sb = new StringBuilder(bytes.remaining());
        while (bytes.hasRemaining()) {
            byte b = bytes.get();
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

    public void gdbloop(StopReason reason) {
        cpu.deactivateBreakpoints();
        final ByteBuffer packetBuffer = ByteBuffer.allocate(8192);
        gdbloop:
        while (true) {
            switch (state) {
                case DISCONNECTED -> {
                    // If we get disconnected while stopped, the CPU should stay stopped. Nothing to do
                    // but wait for a new connection
                    // If we end up needing to wait on multiple things, we can use epoll (or platform equivalent)
                    // via the Selector API
                    try {
                        this.listeningSock.configureBlocking(true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    this.attemptConnect();
                }
                case STOP_REPLY -> {
                    try (var s = new PacketOutputStream(gdbOut);
                         var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                        w.write("S05");
                        state = GDBState.WAITING_FOR_COMMAND;
                    } catch (IOException e) {
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

                        byte command = packetBuffer.get();
                        switch (command) {
                            case '?' -> {
                                //TODO handle different reasons
                                try (var s = new PacketOutputStream(gdbOut);
                                     var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("S05");
                                }
                            }
                            //General Query
                            case 'q' -> {
                                final byte[] Supported = "Supported:".getBytes(StandardCharsets.US_ASCII);
                                final byte[] Attached = "Attached".getBytes(StandardCharsets.US_ASCII);
                                if (ByteBufferUtils.prefixOf(packetBuffer, ByteBuffer.wrap(Supported))) {
                                    packetBuffer.position(packetBuffer.position() + Supported.length);
                                    handleSupported(packetBuffer);
                                } else if (ByteBufferUtils.prefixOf(packetBuffer, ByteBuffer.wrap(Attached))) {
                                    try (var s = new PacketOutputStream(gdbOut);
                                         var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                        w.write("1");
                                    }
                                } else {
                                    unknownCommand(packetBuffer);
                                }
                            }
                            case 'g' -> readGeneralRegisters();
                            case 'G' -> writeGeneralRegisters(packetBuffer);
                            case 'm' -> handleReadMemory(packetBuffer);
                            case 'M' -> handleWriteMemory(packetBuffer);
                            case 'Z' -> {
                                byte type = packetBuffer.get();
                                switch (type) {
                                    case '0' -> handleBreakpointAdd(packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'z' -> {
                                byte type = packetBuffer.get();
                                switch (type) {
                                    case '0' -> handleBreakpointRemove(packetBuffer);
                                    default -> unknownCommand(packetBuffer);
                                }
                            }
                            case 'c' -> {
                                state = GDBState.STOP_REPLY;
                                break gdbloop;
                            }
                            case 'D' -> {
                                try (var s = new PacketOutputStream(gdbOut);
                                     var w = new OutputStreamWriter(s, StandardCharsets.US_ASCII)) {
                                    w.write("OK");
                                }
                                disconnect();
                                break gdbloop;
                            }
                            default -> unknownCommand(packetBuffer);
                        }
                    } catch (IOException e) {
                        disconnect();
                    }
                }
            }
        }
        cpu.activateBreakpoints();
    }
}
