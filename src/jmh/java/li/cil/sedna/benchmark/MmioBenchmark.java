package li.cil.sedna.benchmark;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(MmioBenchmark.INSTRUCTIONS)
public class MmioBenchmark {
    static final int INSTRUCTIONS = 200_000;

    private static final int RAM_SIZE = 32 * 1024 * 1024;
    private static final int CODE_SIZE = (INSTRUCTIONS + 4096) * 4;

    private static final long DEVICE_ADDRESS = 0x10000000L;

    @Param({"ram", "mmio"})
    public String target;

    @Param({"bare", "sv39"})
    public String translation;

    private Vm vm;
    private R5CPU cpu;
    private long codeStart;
    private long address;

    /** A device that does nothing, so the measurement is the access path rather than the device. */
    private static final class NullDevice implements MemoryMappedDevice {
        @Override
        public int getLength() {
            return Vm.PAGE_SIZE;
        }

        @Override
        public int getSupportedSizes() {
            return (1 << Sizes.SIZE_32_LOG2) | (1 << Sizes.SIZE_64_LOG2);
        }

        @Override
        public long load(final int offset, final int sizeLog2) {
            return 0;
        }

        @Override
        public void store(final int offset, final long value, final int sizeLog2) {
        }
    }

    @Setup(Level.Trial)
    public void setUp() {
        final boolean paged = "sv39".equals(translation);
        vm = paged ? Vm.paged(RAM_SIZE) : Vm.bare(RAM_SIZE);
        cpu = vm.cpu();

        codeStart = vm.usableStart();

        if ("mmio".equals(target)) {
            vm.addDevice(DEVICE_ADDRESS, new NullDevice());
            address = DEVICE_ADDRESS;
        } else {
            address = align(codeStart + CODE_SIZE, Vm.PAGE_SIZE);
        }

        // Repeatedly load from the same address, so RAM is a guaranteed TLB hit and any difference
        // is what the device path costs on top.
        vm.fill(codeStart, CODE_SIZE, R5Assembler.ld(2, 1, 0));

        if (paged) {
            if ("mmio".equals(target)) {
                vm.mapPage(DEVICE_ADDRESS);
            }
            vm.enterSupervisor(codeStart);
        }
    }

    @Benchmark
    public long access() {
        vm.registers()[1] = address;
        vm.setProgramCounter(codeStart);
        cpu.step(INSTRUCTIONS);
        return cpu.getInstructionsRetired();
    }

    private static long align(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
