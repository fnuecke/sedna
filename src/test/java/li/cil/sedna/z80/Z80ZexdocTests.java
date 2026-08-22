package li.cil.sedna.z80;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public final class Z80ZexdocTests {
    private static final Path ZEXDOC = Path.of("src/test/data/z80/z80exer/cpm/zexdoc.com");

    @Test
    public void zexdocPassesWithoutErrors() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(ZEXDOC),
                "z80exer submodule not checked out, skipping. Run `git submodule update --init` to run this test.");

        final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        final SimpleMemoryMap ioMap = new SimpleMemoryMap();
        final PhysicalMemory memory = Memory.create(0x10000);
        assertTrue(memoryMap.addDevice(0, memory));

        final Z80CPUBase cpu = (Z80CPUBase) Z80CPU.create(memoryMap, ioMap);

        final byte[] program = Files.readAllBytes(ZEXDOC);
        store(memory, 0x100, program);
        // CP/M shims. zexdoc loads its stack pointer from the word at 6, which doubles as the
        // low bytes of the BDOS trap: 0x76 (HALT); at 5: 0xDB 0x30 0xC9 (IN A,(0x30); RET), so
        // SP starts at 0xC930.
        store(memory, 0, new byte[]{0x76, 0, 0, 0, 0, (byte) 0xDB, 0x30, (byte) 0xC9});

        final StringBuilder output = new StringBuilder();
        assertTrue(ioMap.addDevice(0, new MemoryMappedDevice() {
            @Override
            public int getLength() {
                return 0x10000;
            }

            @Override
            public int getSupportedSizes() {
                return 1 << Sizes.SIZE_8_LOG2;
            }

            @Override
            public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
                if ((offset & 0xFF) == 0x30) {
                    handleBdosCall();
                }
                return 0;
            }

            @Override
            public void store(final int offset, final long value, final int sizeLog2) {
            }

            private void handleBdosCall() throws MemoryAccessException {
                final int function = cpu.r[1]; // C
                if (function == 2) { // Console output, character in E.
                    output.append((char) cpu.r[3]);
                } else if (function == 9) { // Print string at DE, '$'-terminated.
                    int address = (cpu.r[2] << 8) | cpu.r[3];
                    for (int i = 0; i < 0x10000; i++) {
                        final char ch = (char) memoryMap.load(address, Sizes.SIZE_8_LOG2);
                        if (ch == '$') {
                            break;
                        }
                        output.append(ch);
                        address = (address + 1) & 0xFFFF;
                    }
                }
            }
        }));

        cpu.reset(true, 0x100);

        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            while (!cpu.isHalted()) {
                cpu.step(100_000_000);
            }
        });

        final String text = output.toString();
        assertTrue(text.contains("Tests complete"), () -> "zexdoc did not run to completion:\n" + text);
        assertFalse(text.contains("ERROR"), () -> "zexdoc reported errors:\n" + text);
    }

    private static void store(final PhysicalMemory memory, final int address, final byte[] data) throws MemoryAccessException {
        for (int i = 0; i < data.length; i++) {
            memory.store(address + i, data[i], Sizes.SIZE_8_LOG2);
        }
    }
}
