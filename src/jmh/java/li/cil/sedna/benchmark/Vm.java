package li.cil.sedna.benchmark;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.ByteBufferMemory;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import li.cil.sedna.riscv.R5;
import li.cil.sedna.riscv.R5Assembler;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CSR;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class Vm implements AutoCloseable {
    public static final long RAM_START = 0x80000000L;
    public static final int PAGE_SIZE = 1 << R5.PAGE_ADDRESS_SHIFT;

    private static final int PTE_SIZE = 8;
    private static final int ENTRIES_PER_TABLE = PAGE_SIZE / PTE_SIZE;

    private static final int BOOTSTRAP_OFFSET = 0;
    private static final int BOOTSTRAP_SIZE = PAGE_SIZE;

    private static final int SPARE_TABLE_PAGES = 16;

    /** Where in its page a pointer chain node sits. Anywhere but zero, so offsets are exercised. */
    private static final int NODE_OFFSET = 64;

    private final MemoryMap memoryMap;
    private final R5CPU cpu;
    private final int ramSize;

    private final List<PhysicalMemory> ramDevices = new ArrayList<>();
    private final List<Path> tempFiles = new ArrayList<>();

    private final long tableRegionStart;
    private final long tableRegionEnd;
    private long nextFreeTable;

    private long rootTable;
    private final long usableStart;

    private Vm(final int ramSize, final boolean paged, final String backend, final int ramDeviceCount) {
        this.ramSize = ramSize;

        memoryMap = new SimpleMemoryMap();
        final int chunkSize = ramSize / ramDeviceCount;
        if (chunkSize * ramDeviceCount != ramSize || (chunkSize & (PAGE_SIZE - 1)) != 0) {
            throw new IllegalArgumentException("RAM must split into page-aligned chunks");
        }
        for (int i = 0; i < ramDeviceCount; i++) {
            final PhysicalMemory ram = createMemory(backend, chunkSize);
            ramDevices.add(ram);
            memoryMap.addDevice(RAM_START + (long) i * chunkSize, ram);
        }

        cpu = R5CPU.create(memoryMap);
        cpu.reset(true, RAM_START);
        cpu.setXLEN(R5.XLEN_64);

        tableRegionStart = RAM_START + BOOTSTRAP_OFFSET + BOOTSTRAP_SIZE;
        final int tablePages = paged ? identityMappingTablePages(ramSize) + SPARE_TABLE_PAGES : 0;
        tableRegionEnd = tableRegionStart + (long) tablePages * PAGE_SIZE;
        nextFreeTable = tableRegionStart;

        usableStart = tableRegionEnd;
    }

    public static Vm bare(final int ramSize) {
        return bare(ramSize, "unsafe");
    }

    public static Vm bare(final int ramSize, final String backend) {
        return new Vm(ramSize, false, backend, 1);
    }

    public static Vm paged(final int ramSize) {
        return paged(ramSize, "unsafe");
    }

    public static Vm paged(final int ramSize, final String backend) {
        return paged(ramSize, backend, 1);
    }

    public static Vm paged(final int ramSize, final String backend, final int ramDeviceCount) {
        final Vm vm = new Vm(ramSize, true, backend, ramDeviceCount);
        vm.rootTable = vm.allocateTable();
        for (long page = RAM_START; page < vm.ramEnd(); page += PAGE_SIZE) {
            vm.mapPage(page);
        }
        return vm;
    }

    private PhysicalMemory createMemory(final String backend, final int size) {
        switch (backend) {
            case "unsafe":
                return Memory.create(size);
            case "bytebuffer":
                return new ByteBufferMemory(size);
            case "mapped":
                try {
                    final Path file = Files.createTempFile("sedna-jmh-ram", ".bin");
                    tempFiles.add(file);
                    try (final FileChannel channel = FileChannel.open(file,
                        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
                        return new ByteBufferMemory(size, buffer);
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            default:
                throw new IllegalArgumentException("Unknown memory backend: " + backend);
        }
    }

    @Override
    public void close() {
        for (final PhysicalMemory ram : ramDevices) {
            try {
                ram.close();
            } catch (final Exception ignored) {
            }
        }
        for (final Path file : tempFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (final IOException ignored) {
            }
        }
    }

    private static int identityMappingTablePages(final int ramSize) {
        return 2 + ceilDiv(ramSize, ENTRIES_PER_TABLE * PAGE_SIZE);
    }

    public MemoryMap memoryMap() {
        return memoryMap;
    }

    public R5CPU cpu() {
        return cpu;
    }

    public long usableStart() {
        return usableStart;
    }

    public long ramEnd() {
        return RAM_START + ramSize;
    }

    public long rootTable() {
        return rootTable;
    }

    public void addDevice(final long address, final MemoryMappedDevice device) {
        memoryMap.addDevice(address, device);
    }

    public long[] registers() {
        return cpu.getDebugInterface().getGeneralRegisters();
    }

    public void setProgramCounter(final long pc) {
        cpu.getDebugInterface().setProgramCounter(pc);
    }

    ///////////////////////////////////////////////////////////////////
    // Memory

    public void fill(final long address, final int lengthInBytes, final int instruction) {
        for (int offset = 0; offset < lengthInBytes; offset += 4) {
            store(address + offset, instruction, Sizes.SIZE_32_LOG2);
        }
    }

    public void fillPattern(final long address, final int lengthInBytes, final int... instructions) {
        final int patternBytes = instructions.length * 4;
        for (int offset = 0; offset + patternBytes <= lengthInBytes; offset += patternBytes) {
            for (int i = 0; i < instructions.length; i++) {
                store(address + offset + i * 4L, instructions[i], Sizes.SIZE_32_LOG2);
            }
        }
    }

    /** Fills with a 16 bit (compressed) instruction. */
    public void fillCompressed(final long address, final int lengthInBytes, final int instruction) {
        for (int offset = 0; offset < lengthInBytes; offset += 2) {
            store(address + offset, instruction, Sizes.SIZE_16_LOG2);
        }
    }

    public void write(final long address, final int... instructions) {
        for (int i = 0; i < instructions.length; i++) {
            store(address + i * 4L, instructions[i], Sizes.SIZE_32_LOG2);
        }
    }

    public void store64(final long address, final long value) {
        store(address, value, Sizes.SIZE_64_LOG2);
    }

    public long load64(final long address) {
        try {
            return memoryMap.load(address, Sizes.SIZE_64_LOG2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }
    }

    private void store(final long address, final long value, final int sizeLog2) {
        try {
            memoryMap.store(address, value, sizeLog2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Privilege and translation

    public void execute(final int instruction) {
        final long address = RAM_START + BOOTSTRAP_OFFSET;
        write(address, instruction);
        setProgramCounter(address);
        cpu.getDebugInterface().step();
    }

    public void writeCSR(final int csr, final long value) {
        registers()[31] = value;
        execute(R5Assembler.csrrw(0, csr, 31));
    }

    public void setCSRBits(final int csr, final long mask) {
        registers()[31] = mask;
        execute(R5Assembler.csrrs(0, csr, 31));
    }

    public void clearCSRBits(final int csr, final long mask) {
        registers()[31] = mask;
        execute(R5Assembler.csrrc(0, csr, 31));
    }

    public void enterSupervisor(final long pc) {
        writeCSR(R5CSR.SATP, satpFor(rootTable));
        // MPP must be cleared before setting it: it is a two-bit field, and a trap taken from
        // machine mode leaves it holding M (0b11), which OR-ing S (0b01) into does not change --
        // the MRET below would then silently stay in machine mode.
        clearCSRBits(R5CSR.MSTATUS, R5.STATUS_MPP_MASK);
        setCSRBits(R5CSR.MSTATUS, (long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);
        writeCSR(R5CSR.MEPC, pc);
        execute(R5Assembler.MRET);
    }

    public static long satpFor(final long rootTable) {
        return R5.SATP_MODE_SV39 | (rootTable >>> R5.PAGE_ADDRESS_SHIFT);
    }

    public void mapPage(final long address) {
        mapPageIn(rootTable, address);
    }

    public long buildAlternateAddressSpace() {
        final long alternateRoot = allocateTable();
        for (long page = RAM_START; page < ramEnd(); page += PAGE_SIZE) {
            mapPageIn(alternateRoot, page);
        }
        return alternateRoot;
    }

    private void mapPageIn(final long root, final long address) {
        long table = root;
        for (int level = 2; level > 0; level--) {
            final long entryAddress = table + (long) index(address, level) * PTE_SIZE;
            final long pte = load64(entryAddress);
            if ((pte & R5.PTE_V_MASK) == 0) {
                final long next = allocateTable();
                store64(entryAddress, pointerPTE(next));
                table = next;
            } else {
                table = (pte >>> R5.PTE_DATA_BITS) << R5.PAGE_ADDRESS_SHIFT;
            }
        }
        store64(table + (long) index(address, 0) * PTE_SIZE, leafPTE(address));
    }

    private long allocateTable() {
        if (nextFreeTable >= tableRegionEnd) {
            throw new IllegalStateException("Out of page table space; raise SPARE_TABLE_PAGES.");
        }
        final long table = nextFreeTable;
        nextFreeTable += PAGE_SIZE;
        for (int i = 0; i < ENTRIES_PER_TABLE; i++) {
            store64(table + (long) i * PTE_SIZE, 0);
        }
        return table;
    }

    private static int index(final long address, final int level) {
        return (int) ((address >>> (R5.PAGE_ADDRESS_SHIFT + 9 * level)) & 0x1FF);
    }

    private static long pointerPTE(final long table) {
        return ((table >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | R5.PTE_V_MASK;
    }

    private static long leafPTE(final long page) {
        return ((page >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS)
            | R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK | R5.PTE_X_MASK
            | R5.PTE_A_MASK | R5.PTE_D_MASK;
    }

    private static int ceilDiv(final int value, final int divisor) {
        return (value + divisor - 1) / divisor;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Links one node per page into a single cycle, visiting the pages in a strided order so that
     * consecutive accesses are never to adjacent pages.
     *
     * @return the address of the node to start from.
     */
    public long buildPointerChain(final long start, final int nodeCount) {
        final int stride = Math.max(1, nodeCount / 3) | 1;

        long current = start + NODE_OFFSET;
        int index = 0;
        for (int i = 0; i < nodeCount; i++) {
            final int next = (index + stride) % nodeCount;
            final long nextAddress = start + (long) next * PAGE_SIZE + NODE_OFFSET;
            store64(current, nextAddress);
            current = nextAddress;
            index = next;
        }

        return start + NODE_OFFSET;
    }

    public static long align(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
