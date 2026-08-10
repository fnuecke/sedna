package li.cil.sedna.riscv;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;

import static li.cil.sedna.riscv.R5Assembler.*;

public final class Vm {
    public static final long RAM_START = 0x80000000L;

    /** A leaf PTE for a page holding code: valid, readable, writable, executable, accessed, dirty. */
    public static final int PTE_CODE = R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK
        | R5.PTE_X_MASK | R5.PTE_A_MASK | R5.PTE_D_MASK;

    /** The same for a page holding data, i.e. without execute permission. */
    public static final int PTE_DATA = R5.PTE_V_MASK | R5.PTE_R_MASK | R5.PTE_W_MASK
        | R5.PTE_A_MASK | R5.PTE_D_MASK;

    private static final int SCRATCH_REGISTER = 31;

    private final MemoryMap memoryMap;
    private final R5CPU cpu;

    private long scratchAddress = RAM_START;

    public static Vm create(final int ramSize) {
        return new Vm(ramSize);
    }

    private Vm(final int ramSize) {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(RAM_START, Memory.create(ramSize));
        cpu = createCPU(memoryMap, RAM_START);
    }

    public static R5CPU createCPU(final MemoryMap memoryMap, final long pc) {
        final R5CPU cpu = R5CPU.create(memoryMap);
        cpu.reset(true, pc);
        cpu.setXLEN(R5.XLEN_64);
        return cpu;
    }

    public MemoryMap memoryMap() {
        return memoryMap;
    }

    public R5CPU cpu() {
        return cpu;
    }

    public long[] registers() {
        return cpu.getDebugInterface().getGeneralRegisters();
    }

    public long programCounter() {
        return cpu.getDebugInterface().getProgramCounter();
    }

    public void setProgramCounter(final long pc) {
        cpu.getDebugInterface().setProgramCounter(pc);
    }

    public void addDevice(final long address, final MemoryMappedDevice device) {
        memoryMap.addDevice(address, device);
    }

    ///////////////////////////////////////////////////////////////////
    // Memory

    public void store(final long address, final long value, final int sizeLog2) {
        try {
            memoryMap.store(address, value, sizeLog2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }
    }

    public long load(final long address, final int sizeLog2) {
        try {
            return memoryMap.load(address, sizeLog2);
        } catch (final MemoryAccessException e) {
            throw new AssertionError(e);
        }
    }

    public void store64(final long address, final long value) {
        store(address, value, Sizes.SIZE_64_LOG2);
    }

    public long load64(final long address) {
        return load(address, Sizes.SIZE_64_LOG2);
    }

    public void write(final long address, final int... instructions) {
        for (int i = 0; i < instructions.length; i++) {
            store(address + i * 4L, instructions[i], Sizes.SIZE_32_LOG2);
        }
    }

    public void fill(final long address, final int lengthInBytes, final int instruction) {
        for (int offset = 0; offset < lengthInBytes; offset += 4) {
            store(address + offset, instruction, Sizes.SIZE_32_LOG2);
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Execution

    public void setScratchAddress(final long address) {
        scratchAddress = address;
    }

    public void stepOnce() {
        cpu.getDebugInterface().step();
    }

    public long execute(final int instruction) {
        write(scratchAddress, instruction);
        setProgramCounter(scratchAddress);
        cpu.getDebugInterface().step();
        return programCounter();
    }

    ///////////////////////////////////////////////////////////////////
    // Control and status registers

    public long readCSR(final int csr) {
        final long[] registers = registers();
        registers[SCRATCH_REGISTER] = 0;
        execute(csrrs(SCRATCH_REGISTER, csr, 0));
        return registers[SCRATCH_REGISTER];
    }

    public void writeCSR(final int csr, final long value) {
        registers()[SCRATCH_REGISTER] = value;
        execute(csrrw(0, csr, SCRATCH_REGISTER));
    }

    public void setCSRBits(final int csr, final long mask) {
        registers()[SCRATCH_REGISTER] = mask;
        execute(csrrs(0, csr, SCRATCH_REGISTER));
    }

    public void clearCSRBits(final int csr, final long mask) {
        registers()[SCRATCH_REGISTER] = mask;
        execute(csrrc(0, csr, SCRATCH_REGISTER));
    }

    ///////////////////////////////////////////////////////////////////
    // Privilege and translation

    public void enterSupervisor(final long satp, final long pc) {
        writeCSR(R5CSR.SATP, satp);
        // MPP must be cleared before setting it: it is a two-bit field, and a trap taken from
        // machine mode leaves it holding M (0b11), which OR-ing S (0b01) into does not change --
        // the MRET below would then silently stay in machine mode.
        clearCSRBits(R5CSR.MSTATUS, R5.STATUS_MPP_MASK);
        setCSRBits(R5CSR.MSTATUS, (long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);
        writeCSR(R5CSR.MEPC, pc);
        execute(MRET);
    }

    public void flushPage(final long address) {
        registers()[SCRATCH_REGISTER] = address;
        execute(sfenceVma(SCRATCH_REGISTER));
    }

    public static long satpSv39(final long rootTable) {
        return R5.SATP_MODE_SV39 | (rootTable >>> R5.PAGE_ADDRESS_SHIFT);
    }

    public static int pageTableIndex(final long address, final int level) {
        return (int) ((address >>> (R5.PAGE_ADDRESS_SHIFT + 9 * level)) & 0x1FF);
    }

    public static long pointerPTE(final long table) {
        return ((table >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | R5.PTE_V_MASK;
    }

    public static long leafPTE(final long page) {
        return leafPTE(page, PTE_CODE);
    }

    public static long leafPTE(final long page, final int flags) {
        return ((page >>> R5.PAGE_ADDRESS_SHIFT) << R5.PTE_DATA_BITS) | flags;
    }
}
