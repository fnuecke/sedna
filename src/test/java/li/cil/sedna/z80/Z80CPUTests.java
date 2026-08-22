package li.cil.sedna.z80;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public final class Z80CPUTests {
    private SimpleMemoryMap memoryMap;
    private PhysicalMemory memory;
    private Z80CPUBase cpu;

    @BeforeEach
    public void setupEach() {
        memoryMap = new SimpleMemoryMap();
        memory = Memory.create(0x10000);
        assertTrue(memoryMap.addDevice(0, memory));
        cpu = (Z80CPUBase) Z80CPU.create(memoryMap, new SimpleMemoryMap());
    }

    @Test
    public void addImmediateAndHalt() throws MemoryAccessException {
        run(0x3E, 0x05, // LD A,5
                0xC6, 0x03, // ADD A,3
                0x76);      // HALT
        assertEquals(8, cpu.r[7]);
        assertTrue(cpu.isHalted());
    }

    @Test
    public void djnzLoops() throws MemoryAccessException {
        run(0x06, 0x0A,  // LD B,10
                0x3C,        // INC A (A starts at 0 after XOR below? A is 0xFF on reset)
                0xAF,        // XOR A -- clear A; placed after INC to also test order independence
                0x3C,        // INC A
                0x10, 0xFD,  // DJNZ -3 (back to INC A)
                0x76);       // HALT
        assertEquals(10, cpu.r[7]);
        assertEquals(0, cpu.r[0]);
        assertTrue(cpu.isHalted());
    }

    @Test
    public void callAndReturn() throws MemoryAccessException {
        run(0x31, 0x00, 0x20, // LD SP,0x2000
                0xCD, 0x09, 0x01, // CALL 0x0109
                0x76,             // HALT
                0x00, 0x00,
                0x3E, 0x42,       // 0x0109: LD A,0x42
                0xC9);            // RET
        assertEquals(0x42, cpu.r[7]);
        assertEquals(0x2000, cpu.sp);
        assertTrue(cpu.isHalted());
    }

    @Test
    public void prefixedLoadsIndexRegister() throws MemoryAccessException {
        run(0xDD, 0x21, 0x34, 0x12, // LD IX,0x1234
                0xFD, 0x21, 0x78, 0x56, // LD IY,0x5678
                0x76);                  // HALT
        assertEquals(0x1234, cpu.ixiy[0]);
        assertEquals(0x5678, cpu.ixiy[1]);
    }

    @Test
    public void prefixFallsThroughToUnprefixedInstruction() throws MemoryAccessException {
        run(0x06, 0x01, // LD B,1
                0xDD, 0x04, // (DD) INC B -- prefix does not apply
                0x76);      // HALT
        assertEquals(2, cpu.r[0]);
    }

    @Test
    public void prefixChainsReDecode() throws MemoryAccessException {
        run(0xDD, 0xDD, 0xFD, 0x21, 0x34, 0x12, // LD IY,0x1234 behind two dead prefixes
                0x76);                              // HALT
        assertEquals(0x1234, cpu.ixiy[1]);
        assertEquals(0, cpu.ixiy[0]);
    }

    @Test
    public void memptrIsVisibleThroughBitOnHL() throws MemoryAccessException {
        // LD A,(0x2827) sets MEMPTR to 0x2828; BIT 5,(HL) then takes X/Y from MEMPTR's high byte.
        run(0x21, 0x00, 0x18,       // LD HL,0x1800
                0x3A, 0x27, 0x28,       // LD A,(0x2827)
                0xCB, 0x6E,             // BIT 5,(HL)
                0x76);                  // HALT
        assertEquals(0x28 & 0x28, cpu.f & 0x28);
    }

    @Test
    public void serializationRoundTrip() throws MemoryAccessException {
        Sedna.initialize();

        for (int i = 0; i < 5; i++) {
            memory.store(0x100 + i, new int[]{0x3E, 0x05, 0xC6, 0x03, 0x76}[i], Sizes.SIZE_8_LOG2);
        }
        cpu.reset(true, 0x100);
        cpu.step(7); // Exactly LD A,5.
        assertEquals(5, cpu.r[7]);
        assertFalse(cpu.isHalted());

        final ByteBuffer serialized = assertDoesNotThrow(() -> BinarySerialization.serialize(cpu, Z80CPU.class));
        final Z80CPUBase restored = (Z80CPUBase) Z80CPU.create(memoryMap, new SimpleMemoryMap());
        assertDoesNotThrow(() -> BinarySerialization.deserialize(serialized, Z80CPU.class, restored));

        assertEquals(cpu.pc, restored.pc);
        assertEquals(cpu.cycles, restored.cycles);
        assertEquals(5, restored.r[7]);

        serialized.rewind();
        final ByteBuffer reserialized = assertDoesNotThrow(() -> BinarySerialization.serialize(restored, Z80CPU.class));
        assertEquals(serialized, reserialized);

        restored.step(1_000);
        assertTrue(restored.isHalted());
        assertEquals(8, restored.r[7]);
    }

    private void run(final int... program) throws MemoryAccessException {
        for (int i = 0; i < program.length; i++) {
            memory.store(0x100 + i, program[i], Sizes.SIZE_8_LOG2);
        }
        cpu.reset(true, 0x100);
        cpu.step(10_000);
        assertTrue(cpu.isHalted(), "program did not reach HALT");
    }
}
