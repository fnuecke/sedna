package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.flash.FlashMemoryDevice;
import li.cil.sedna.device.memory.ByteBufferMemory;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class HostPointerTLBTests {
    private static final long RAM_START = 0x80000000L;
    private static final int RAM_SIZE = 64 * 1024;
    private static final long TEST_VALUE = 0x123456789ABCDEF0L;

    @Test
    public void heapBackedMemoryExecutesViaFallbackTier() throws MemoryAccessException {
        final ByteBufferMemory ram = new ByteBufferMemory(RAM_SIZE, ByteBuffer.allocate(RAM_SIZE));
        assertEquals(0, ram.getHostAddress(), "heap buffers must not expose a host address");
        assertEquals(TEST_VALUE, runStoreLoadProgram(ram));
    }

    @Test
    public void directBackedMemoryExecutesViaDirectTier() throws MemoryAccessException {
        final PhysicalMemory ram = Memory.create(RAM_SIZE);
        Assumptions.assumeTrue(ram.getHostAddress() != 0, "no direct host access on this JVM");
        assertEquals(TEST_VALUE, runStoreLoadProgram(ram));
    }

    @Test
    public void executesFromDeviceWithoutHostAddress() {
        final FlashMemoryDevice flash = new FlashMemoryDevice(0x100);
        flash.getData().putInt(0, addi(1, 0, 42));
        flash.getData().putInt(4, jal(0, 0));

        final MemoryMap memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(0x1000, flash);
        final R5CPU cpu = Vm.createCPU(memoryMap, 0x1000);

        cpu.step(16);

        assertEquals(42, cpu.getDebugInterface().getGeneralRegisters()[1]);
    }

    private static long runStoreLoadProgram(final PhysicalMemory ram) throws MemoryAccessException {
        final MemoryMap memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(RAM_START, ram);
        final R5CPU cpu = Vm.createCPU(memoryMap, RAM_START);

        final long dataAddress = RAM_START + 0x1000;
        try {
            memoryMap.store(RAM_START, sd(1, 2, 0), Sizes.SIZE_32_LOG2);
            memoryMap.store(RAM_START + 4, ld(3, 2, 0), Sizes.SIZE_32_LOG2);
            memoryMap.store(RAM_START + 8, jal(0, 0), Sizes.SIZE_32_LOG2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }

        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = TEST_VALUE;
        registers[2] = dataAddress;

        cpu.step(16);

        assertEquals(TEST_VALUE, memoryMap.load(dataAddress, Sizes.SIZE_64_LOG2),
                "store must be visible through the memory map");
        assertNotEquals(0, cpu.getDebugInterface().getGeneralRegisters()[3]);
        return cpu.getDebugInterface().getGeneralRegisters()[3];
    }
}
