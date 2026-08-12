package li.cil.sedna;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.MemoryMaps;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class MemoryMapTests {
    private static final long ADDRESS = 0x80000000L;

    private MemoryMap memoryMap;

    @BeforeEach
    public void setupEach() {
        memoryMap = new SimpleMemoryMap();
    }

    @Test
    public void continuousMemorySizeIsComputedCorrectly() {
        final PhysicalMemory memory1 = mock(PhysicalMemory.class);
        when(memory1.getLength()).thenReturn(0x1000);

        final PhysicalMemory memory2 = mock(PhysicalMemory.class);
        when(memory2.getLength()).thenReturn(0x1000);

        assertTrue(memoryMap.addDevice(0x80000000L, memory1));
        assertTrue(memoryMap.addDevice(0x80001000L, memory2));

        assertEquals(0x2000, MemoryMaps.getContinuousMemorySize(memoryMap, 0x80000000L));
    }

    @Test
    public void interruptedMemorySizeIsComputedCorrectly() {
        final PhysicalMemory memory1 = mock(PhysicalMemory.class);
        when(memory1.getLength()).thenReturn(0x1000);

        final PhysicalMemory memory2 = mock(PhysicalMemory.class);
        when(memory2.getLength()).thenReturn(0x1000);

        assertTrue(memoryMap.addDevice(0x80000000L, memory1));
        assertTrue(memoryMap.addDevice(0x80001000L + 1, memory2));

        assertEquals(0x1000, MemoryMaps.getContinuousMemorySize(memoryMap, 0x80000000L));
        assertEquals(0x1000, MemoryMaps.getContinuousMemorySize(memoryMap, 0x80001001L));
    }

    @Test
    public void emptyMemorySizeIsComputedCorrectly() {
        final PhysicalMemory memory1 = mock(PhysicalMemory.class);
        when(memory1.getLength()).thenReturn(0x1000);

        assertEquals(0, MemoryMaps.getContinuousMemorySize(memoryMap, 0));
        assertEquals(0, MemoryMaps.getContinuousMemorySize(memoryMap, 0x80008000L));
    }

    @Test
    public void mappedAccessesAreServiced() throws MemoryAccessException {
        memoryMap.addDevice(ADDRESS, Memory.create(0x1000));

        memoryMap.store(ADDRESS, 0x12345678, Sizes.SIZE_32_LOG2);

        assertEquals(0x12345678, memoryMap.load(ADDRESS, Sizes.SIZE_32_LOG2));
    }

    @Test
    public void unmappedAccessesAreReported() {
        memoryMap.addDevice(ADDRESS, Memory.create(0x1000));

        assertThrows(MemoryAccessException.class, () -> memoryMap.load(0, Sizes.SIZE_32_LOG2));
        assertThrows(MemoryAccessException.class, () -> memoryMap.store(0, 1, Sizes.SIZE_32_LOG2));

        // Just past the end of the mapped device.
        assertThrows(MemoryAccessException.class, () -> memoryMap.load(ADDRESS + 0x1000, Sizes.SIZE_32_LOG2));
        assertThrows(MemoryAccessException.class, () -> memoryMap.store(ADDRESS + 0x1000, 1, Sizes.SIZE_32_LOG2));
    }

    @Test
    public void accessesOfAnUnsupportedSizeAreReported() {
        final PhysicalMemory device = mock(PhysicalMemory.class);
        when(device.getLength()).thenReturn(0x1000);
        when(device.getSupportedSizes()).thenReturn(1 << Sizes.SIZE_32_LOG2);

        assertTrue(memoryMap.addDevice(ADDRESS, device));

        assertThrows(MemoryAccessException.class, () -> memoryMap.load(ADDRESS, Sizes.SIZE_8_LOG2));
        assertThrows(MemoryAccessException.class, () -> memoryMap.store(ADDRESS, 1, Sizes.SIZE_8_LOG2));
    }
}
