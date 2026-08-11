package li.cil.sedna.benchmark;

import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.rtc.SystemTimeRealTimeCounter;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Needs the buildroot images; defaults to the sibling checkout's generated resources and can be
 * pointed elsewhere via {@code -Pjmh.images=<dir>} (a directory containing {@code fw_jump.bin},
 * {@code Image} and {@code rootfs.ext2}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BootBenchmark {
    private static final int RAM_SIZE = 32 * 1024 * 1024;
    private static final long KERNEL_OFFSET = 0x200000;
    private static final long MAX_CYCLES = 4_000_000_000L;
    private static final long FIRST_OUTPUT_LIMIT = 100_000_000L;
    private static final byte[] MARKER = "login:".getBytes(StandardCharsets.US_ASCII);

    private byte[] firmware;
    private byte[] kernel;
    private byte[] rootfs;

    private R5Board board;
    private PhysicalMemory memory;
    private UART16550A uart;

    @Setup(Level.Trial)
    public void loadImages() throws IOException {
        // Registers the device tree providers; without them the DTB the guest gets is unusable.
        Sedna.initialize();

        final Path dir = Path.of(System.getProperty("sedna.benchmark.images",
            "../buildroot/src/main/resources/generated"));
        if (!Files.isRegularFile(dir.resolve("Image"))) {
            throw new IllegalStateException("Buildroot images not found in " + dir.toAbsolutePath()
                + "; build buildroot or pass -Pjmh.images=<dir>.");
        }
        firmware = Files.readAllBytes(dir.resolve("fw_jump.bin"));
        kernel = Files.readAllBytes(dir.resolve("Image"));
        rootfs = Files.readAllBytes(dir.resolve("rootfs.ext2"));
    }

    @Setup(Level.Invocation)
    public void setUp() throws Exception {
        board = new R5Board();
        memory = Memory.create(RAM_SIZE);
        uart = new UART16550A();
        final GoldfishRTC rtc = new GoldfishRTC(SystemTimeRealTimeCounter.get());
        final VirtIOBlockDevice hdd = new VirtIOBlockDevice(board.getMemoryMap(),
            ByteBufferBlockDevice.createFromStream(new ByteArrayInputStream(rootfs), false));
        final VirtIOFileSystemDevice fs = new VirtIOFileSystemDevice(board.getMemoryMap(),
            "host_fs", new HostFileSystem(Files.createTempDirectory("sedna-jmh-9p").toFile()));

        uart.getInterrupt().set(0xA, board.getInterruptController());
        rtc.getInterrupt().set(0xB, board.getInterruptController());
        hdd.getInterrupt().set(0x1, board.getInterruptController());
        fs.getInterrupt().set(0x2, board.getInterruptController());

        board.addDevice(0x80000000L, memory);
        board.addDevice(uart);
        board.addDevice(rtc);
        board.addDevice(hdd);
        board.addDevice(fs);

        board.setBootArguments("root=/dev/vda rw");
        board.setStandardOutputDevice(uart);

        board.reset();
        copyToMemory(memory, firmware, 0);
        copyToMemory(memory, kernel, KERNEL_OFFSET);
        board.initialize();
        board.setRunning(true);
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws Exception {
        memory.close();
    }

    private static void copyToMemory(final PhysicalMemory memory, final byte[] data, final long offset) {
        try {
            for (int i = 0; i < data.length; i++) {
                memory.store((int) offset + i, data[i], Sizes.SIZE_8_LOG2);
            }
        } catch (final MemoryAccessException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Benchmark
    public long boot() {
        final byte[] tail = new byte[4096];
        int tailLength = 0;
        int matched = 0;
        long cycles = 0;
        while (cycles < MAX_CYCLES) {
            board.step(1000);
            cycles += 1000;

            int value;
            while ((value = uart.read()) != -1) {
                if (tailLength == tail.length) {
                    System.arraycopy(tail, tail.length / 2, tail, 0, tail.length / 2);
                    tailLength = tail.length / 2;
                }
                tail[tailLength++] = (byte) value;
                if (value == MARKER[matched]) {
                    matched++;
                    if (matched == MARKER.length) {
                        return board.getCpu().getInstructionsRetired();
                    }
                } else {
                    matched = value == MARKER[0] ? 1 : 0;
                }
            }

            if (cycles >= FIRST_OUTPUT_LIMIT && tailLength == 0) {
                throw new AssertionError("no UART output within " + cycles + " cycles; " + sampleState());
            }
        }
        throw new AssertionError("login prompt not reached within " + MAX_CYCLES
            + " cycles; " + sampleState()
            + "; UART tail:\n" + new String(tail, 0, tailLength, StandardCharsets.US_ASCII));
    }

    private String sampleState() {
        final StringBuilder pcs = new StringBuilder("pc samples:");
        for (int i = 0; i < 5; i++) {
            pcs.append(String.format(" %x", board.getCpu().getDebugInterface().getProgramCounter()));
            board.step(1000);
        }
        pcs.append(", minstret ").append(board.getCpu().getInstructionsRetired());
        return pcs.toString();
    }
}
