package li.cil.sedna;

import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.memory.MemoryMaps;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class MemoryMapTests {
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
}
