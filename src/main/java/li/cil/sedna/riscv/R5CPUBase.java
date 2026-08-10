package li.cil.sedna.riscv;

import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.gdbstub.CPUDebugInterface;
import li.cil.sedna.instruction.InstructionDefinition.Branch;
import li.cil.sedna.instruction.InstructionDefinition.Field;
import li.cil.sedna.instruction.InstructionDefinition.Instruction;
import li.cil.sedna.instruction.InstructionDefinition.InstructionSize;
import li.cil.sedna.instruction.InstructionDefinition.ProgramCounter;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;
import li.cil.sedna.utils.SoftDouble;
import li.cil.sedna.utils.SoftFloat;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * RISC-V RV64GC implementation.
 * <p>
 * Based on <a href="https://github.com/riscv/riscv-isa-manual/releases">the ISA specifications</a>.
 * <ul>
 * <li>Volume I: User-Level ISA 20191214-draft (October 18, 2020)</li>
 * <li>Volume II: Privileged Architecture v1.12-draft (October 18, 2020)</li>
 * </ul>
 * <p>
 * Limitations:
 * <ul>
 * <li>The fence operations are implemented as no-ops.</li>
 * </ul>
 */
@Serialized
public abstract class R5CPUBase implements R5CPU {
    private static final int PC_INIT = 0x1000; // Initial position of program counter.

    // UBE, SBE, MBE hardcoded to zero for little endianness.
    private static final long MSTATUS_MASK = ~R5.STATUS_UBE_MASK & ~R5.STATUS_SBE_MASK & ~R5.STATUS_MBE_MASK;

    // No time and no high perf counters.
    private static final int COUNTEREN_MASK = R5.MCOUNTERN_CY | R5.MCOUNTERN_IR;

    // Supervisor status (sstatus) CSR mask over mstatus. UIE/UPIE are hardwired to zero since
    // we do not implement the N (user-level interrupts) extension.
    private static final long SSTATUS_MASK = (R5.STATUS_SIE_MASK | R5.STATUS_SPIE_MASK |
        R5.STATUS_SPP_MASK | R5.STATUS_FS_MASK |
        R5.STATUS_XS_MASK | R5.STATUS_SUM_MASK |
        R5.STATUS_MXR_MASK | R5.STATUS_UXL_MASK);

    // Translation look-aside buffer config.
    private static final int TLB_SIZE = 256; // Must be a power of two for fast modulo via `& (TLB_SIZE - 1)`.

    ///////////////////////////////////////////////////////////////////
    // RV32I / RV64I
    protected long pc; // Program counter.
    private byte mxl; // Current MXLEN, stored to restore after privilege change.
    private int xlen; // Current XLEN, allows switching between RV32I and RV64I.
    private final long[] x = new long[32]; // Integer registers. We use sign-extended longs for 32 bit mode.

    ///////////////////////////////////////////////////////////////////
    // RV64FD
    private final long[] f = new long[32]; // Float registers.
    private final SoftFloat.Flags fflags = new SoftFloat.Flags(); // flags = fcsr[4:0] := NV . DZ . OF . UF . NX
    private byte frm; // fcsr[7:5]
    private byte fs; // FS field of mstatus, separate for convenience.

    private final transient SoftFloat fpu32 = new SoftFloat(fflags);
    private final transient SoftDouble fpu64 = new SoftDouble(fflags);

    ///////////////////////////////////////////////////////////////////
    // RV64A
    private long reservation_set = -1L; // Reservation set for RV64A's LR/SC.

    ///////////////////////////////////////////////////////////////////
    // User-level CSRs
    /**
     * Cycles elapsed. Advances while the hart is idle in WFI, since the clock keeps running, which
     * is what makes it usable as a time source via {@link #getTime()}.
     */
    protected long mcycle;

    /**
     * Instructions executed. Unlike {@link #mcycle} this does <em>not</em> advance while the hart is
     * idle, which makes it the counter to divide by when measuring how much work was actually done.
     * <p>
     * Slight deviation from the specification: an instruction that raises an exception has already
     * been counted by the time it turns out not to retire, so traps are included here. Traps are
     * rare next to retired instructions, and for measuring emulator throughput counting the work
     * spent decoding and attempting them is the more useful measure anyway.
     */
    protected long minstret;

    // Machine-level CSRs
    private long mstatus; // Machine Status Register
    private long mtvec; // Machine Trap-Vector Base-Address Register; 0b11=Mode: 0=direct, 1=vectored
    private long medeleg, mideleg; // Machine Trap Delegation Registers
    private final AtomicLong mip = new AtomicLong(); // Pending Interrupts
    private long mie; // Enabled Interrupts
    private int mcounteren; // Machine Counter-Enable Register
    private long mscratch; // Machine Scratch Register
    private long mepc; // Machine Exception Program Counter
    private long mcause; // Machine Cause Register
    private long mtval; //  Machine Trap Value Register

    // Supervisor-level CSRs
    private long stvec; // Supervisor Trap Vector Base Address Register; 0b11=Mode: 0=direct, 1=vectored
    private int scounteren; // Supervisor Counter-Enable Register
    private long sscratch; // Supervisor Scratch Register
    private long sepc; // Supervisor Exception Program Counter
    private long scause; // Supervisor Cause Register
    private long stval; // Supervisor Trap Value Register
    private long satp; // Supervisor Address Translation and Protection Register

    ///////////////////////////////////////////////////////////////////
    // Misc. state
    private int priv; // Current privilege level.
    private boolean waitingForInterrupt;

    ///////////////////////////////////////////////////////////////////
    // Memory access

    // Translation look-aside buffers, stored as structure-of-arrays: a lookup reads one long from
    // a dense 2 KiB hash array instead of resolving a separately-allocated entry object, the data
    // arrays are only touched on a hit, and a flush is a plain array fill. The hash encodes page
    // address plus privilege tag (see below); -1 marks an empty slot, which no valid combination
    // can produce. Payload slots are only ever read after their hash matched, so stale values in
    // them are harmless.
    private final transient long[] fetchTLBHash = new long[TLB_SIZE];
    private final transient long[] fetchTLBToOffset = new long[TLB_SIZE];
    private final transient MemoryMappedDevice[] fetchTLBDevice = new MemoryMappedDevice[TLB_SIZE];
    private final transient LongSet[] fetchTLBBreakpoints = new LongSet[TLB_SIZE];
    private final transient long[] loadTLBHash = new long[TLB_SIZE];
    private final transient long[] loadTLBToOffset = new long[TLB_SIZE];
    private final transient MemoryMappedDevice[] loadTLBDevice = new MemoryMappedDevice[TLB_SIZE];
    private final transient long[] storeTLBHash = new long[TLB_SIZE];
    private final transient long[] storeTLBToOffset = new long[TLB_SIZE];
    private final transient MemoryMappedDevice[] storeTLBDevice = new MemoryMappedDevice[TLB_SIZE];

    // Separate look-aside buffers for memory mapped devices, which the TLBs above deliberately
    // exclude: those cache whole pages, and devices are packed with only 8-byte alignment, so a
    // sub-page device may share its page with a neighbor. These entries therefore also carry the
    // virtual address window the device actually occupies within the page; accesses outside it
    // (a neighboring device, an unmapped hole) fall through to the slow path. Only consulted after
    // a main-TLB miss, so the RAM fast path pays nothing for this. Few pages hold devices, so this
    // can be much smaller than the main TLBs.
    private static final int MMIO_TLB_SIZE = 64;
    private final transient MMIOTLBEntry[] mmioLoadTLB = new MMIOTLBEntry[MMIO_TLB_SIZE];
    private final transient MMIOTLBEntry[] mmioStoreTLB = new MMIOTLBEntry[MMIO_TLB_SIZE];

    // TLB entries are keyed by page address, whose low bits are always zero, so the privilege level
    // the translation was made under rides along in them. Entries made under different privilege
    // levels then simply fail to match instead of having to be thrown away, which is what lets a
    // privilege change avoid flushing. Derived state, recomputed whenever priv or mstatus change.
    private transient long fetchTLBTag;
    private transient long dataTLBTag;

    // Access to physical memory for load/store operations.
    private final transient MemoryMap physicalMemory;

    ///////////////////////////////////////////////////////////////////
    // Stepping
    private int cycleDebt; // Traces may lead to us running more cycles than given, remember to pay it back.

    // The current step() call's cycle budget end: a backward jump may continue in-trace only while mcycle
    // is below this, which ensures guest loops still yield (allowing interrupts e.g.).
    protected transient long cycleLimit;

    ///////////////////////////////////////////////////////////////////
    // Real time counter -- at least in RISC-V Linux 5.1 the mtime CSR is needed in add_device_randomness
    // where it doesn't use the SBI. Not implementing it would cause an illegal instruction exception
    // halting the system.
    private final transient RealTimeCounter rtc;
    private transient int cycleFrequency = 50_000_000;
    protected final transient DebugInterface debugInterface = new DebugInterface();

    R5CPUBase(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        this.rtc = rtc != null ? rtc : this;
        this.physicalMemory = physicalMemory;

        // The TLB hash arrays are initialized by the flushTLB() in reset() below.
        for (int i = 0; i < MMIO_TLB_SIZE; i++) {
            mmioLoadTLB[i] = new MMIOTLBEntry();
        }
        for (int i = 0; i < MMIO_TLB_SIZE; i++) {
            mmioStoreTLB[i] = new MMIOTLBEntry();
        }

        reset();
    }

    @Override
    public long getISA() {
        return misa();
    }

    public void setXLEN(final int value) {
        mxl = (byte) R5.mxl(value);
        this.xlen = value;

        mstatus = mstatus
            & ~(R5.STATUS_UXL_MASK | R5.STATUS_SXL_MASK)
            | (R5.mxl(value) << R5.STATUS_UXL_SHIFT)
            | (R5.mxl(value) << R5.STATUS_SXL_SHIFT);

        if (value == R5.XLEN_32) {
            for (int i = 0; i < x.length; i++) {
                x[i] = (int) x[i];
            }
        }
    }

    @Override
    public void reset() {
        reset(true, PC_INIT);
    }

    @Override
    public void reset(final boolean hard, final long pc) {
        this.pc = pc;
        waitingForInterrupt = false;

        // Volume 2, 3.3 Reset
        priv = R5.PRIVILEGE_M;
        mstatus = mstatus & ~R5.STATUS_MIE_MASK;
        mstatus = mstatus & ~R5.STATUS_MPRV_MASK;
        mcause = 0;

        // Volume 2, 3.1.1: The MXL field is always set to the widest supported ISA variant at reset.
        mxl = (byte) R5.mxl(R5.XLEN_64);
        xlen = R5.XLEN_64;

        flushTLB();
        updateTLBTags();

        if (hard) {
            Arrays.fill(x, 0);

            reservation_set = -1;

            mcycle = 0;
            minstret = 0;

            mstatus = (R5.mxl(xlen) << R5.STATUS_UXL_SHIFT) |
                (R5.mxl(xlen) << R5.STATUS_SXL_SHIFT);
            mtvec = 0;
            medeleg = 0;
            mideleg = 0;
            mip.set(0);
            mie = 0;
            mcounteren = 0;
            mscratch = 0;
            mepc = 0;
            mtval = 0;

            stvec = 0;
            scounteren = 0;
            sscratch = 0;
            sepc = 0;
            scause = 0;
            stval = 0;
            satp = 0;
        }
    }

    @Override
    public void invalidateCaches() {
        flushTLB();
        updateTLBTags();
    }

    @Override
    public long getTime() {
        return mcycle;
    }

    @Override
    public long getInstructionsRetired() {
        return minstret;
    }

    @Override
    public int getFrequency() {
        return cycleFrequency;
    }

    @Override
    public void setFrequency(final int value) {
        cycleFrequency = value;
    }

    @Override
    public CPUDebugInterface getDebugInterface() {
        return debugInterface;
    }

    @Override
    public void raiseInterrupts(final int mask) {
        mip.updateAndGet(operand -> operand | mask);
        if (waitingForInterrupt && (mip.get() & mie) != 0) {
            waitingForInterrupt = false;
        }
    }

    @Override
    public void lowerInterrupts(final int mask) {
        mip.updateAndGet(operand -> operand & ~mask);
    }

    @Override
    public int getRaisedInterrupts() {
        return (int) mip.get();
    }

    public void step(int cycles) {
        final int paidDebt = Math.min(cycles, cycleDebt);
        cycles -= paidDebt;
        cycleDebt -= paidDebt;

        if (waitingForInterrupt) {
            mcycle += cycles;
            return;
        }

        final long cycleLimit = mcycle + cycles;
        this.cycleLimit = cycleLimit;
        while (!waitingForInterrupt && mcycle < cycleLimit) {
            final long pending = mip.get() & mie;
            if (pending != 0) {
                raiseInterrupt(pending);
            }

            interpret(false, false);
        }

        if (waitingForInterrupt && mcycle < cycleLimit) {
            mcycle = cycleLimit;
        }

        cycleDebt += (int) (mcycle - cycleLimit);
    }

    ///////////////////////////////////////////////////////////////////
    // Interpretation

    private long misa() {
        // Base ISA descriptor CSR (misa) (V2p16).
        return (R5.mxl(xlen) << R5.mxlShift(xlen)) | R5.isa('I', 'M', 'A', 'C', 'F', 'D', 'S', 'U');
    }

    private long getSupervisorStatusMask() {
        return SSTATUS_MASK | R5.getStatusStateDirtyMask(xlen);
    }

    private void interpret(final boolean singleStep, final boolean ignoreBreakpoints) {
        // The idea here is to run many sequential instructions with very little overhead.
        // We only need to exit the inner loop when we either leave the page we started in,
        // jump around (jumps, conditionals) or some state that influences how memory access
        // happens changes (e.g. satp).
        // For regular execution, we grab one cache entry and keep simply incrementing our
        // position inside it. This does bring with it one important point to be wary of:
        // the value of the program counter field will not match our actual current execution
        // location. So whenever we need to access the "real" PC and when leaving the loop
        // we have to update the field to its correct value.
        // Also noteworthy is that we actually only run until the last position where a 32bit
        // instruction would fully fit a page. The last 16bit in a page may be the start of
        // a 32bit instruction spanning two pages, a special case we handle outside the loop.
        try {
            final int cacheIndex = fetchPage(pc);
            final MemoryMappedDevice device = fetchTLBDevice[cacheIndex];
            final int instOffset = (int) (pc + fetchTLBToOffset[cacheIndex]);
            final int instEnd = instOffset - (int) (pc & R5.PAGE_ADDRESS_MASK) // Page start.
                + ((1 << R5.PAGE_ADDRESS_SHIFT) - 2); // Page size minus 16bit.

            int inst;
            try {
                if (instOffset < instEnd) { // Likely case, instruction fully inside page.
                    inst = (int) device.load(instOffset, Sizes.SIZE_32_LOG2);
                } else { // Unlikely case, instruction may leave page if it is 32bit.
                    inst = (short) device.load(instOffset, Sizes.SIZE_16_LOG2) & 0xFFFF;
                    if ((inst & 0b11) == 0b11) { // 32bit instruction.
                        final int highIndex = fetchPage(pc + 2);
                        final MemoryMappedDevice highDevice = fetchTLBDevice[highIndex];
                        final long highOffset = fetchTLBToOffset[highIndex];
                        inst |= (int) (highDevice.load((int) (pc + 2 + highOffset), Sizes.SIZE_16_LOG2) << 16);
                    }
                }
            } catch (final MemoryAccessException e) {
                raiseException(R5.EXCEPTION_FAULT_FETCH, pc);
                return;
            }

            if (xlen == R5.XLEN_32) {
                interpretTrace32(device, inst, pc, instOffset, singleStep ? 0 : instEnd, ignoreBreakpoints ? null : fetchTLBBreakpoints[cacheIndex]);
            } else {
                interpretTrace64(device, inst, pc, instOffset, singleStep ? 0 : instEnd, ignoreBreakpoints ? null : fetchTLBBreakpoints[cacheIndex]);
            }
        } catch (final R5MemoryAccessException e) {
            raiseException(e.getType(), e.getAddress());
        }
    }

    protected abstract void interpretTrace32(final MemoryMappedDevice device, int inst, long pc, int instOffset, final int instEnd, final LongSet breakpoints);

    protected abstract void interpretTrace64(final MemoryMappedDevice device, int inst, long pc, int instOffset, final int instEnd, final LongSet breakpoints);

    protected static R5IllegalInstructionException illegalInstruction() {
        return new R5IllegalInstructionException();
    }

    ///////////////////////////////////////////////////////////////////
    // CSR

    private boolean csrrwx(final int rd, final long newValue, final int csr) throws R5IllegalInstructionException {
        final boolean exitTrace;

        checkCSR(csr, true);
        if (rd != 0) { // Explicit check, spec says no read side-effects when rd = 0.
            final long oldValue = readCSR(csr);
            exitTrace = writeCSR(csr, newValue);
            x[rd] = oldValue; // Write to register last, avoid lingering side-effect when write errors.
        } else {
            exitTrace = writeCSR(csr, newValue);
        }

        return exitTrace;
    }

    private boolean csrrscx(final int rd, final int rs1, final int csr, final long mask, final boolean isSet) throws R5IllegalInstructionException {
        final boolean mayChange = rs1 != 0;

        if (mayChange) {
            checkCSR(csr, true);
            final long value = readCSR(csr);
            final long masked = isSet ? (mask | value) : (~mask & value);
            final boolean exitTrace = writeCSR(csr, masked);
            if (rd != 0) {
                x[rd] = value;
            }
            return exitTrace;
        } else if (rd != 0) {
            checkCSR(csr, false);
            x[rd] = readCSR(csr);
        }

        return false;
    }

    private void checkCSR(final int csr, final boolean throwIfReadonly) throws R5IllegalInstructionException {
        if (throwIfReadonly && ((csr >= 0xC00 && csr <= 0xC1F) || (csr >= 0xC80 && csr <= 0xC9F)))
            throw new R5IllegalInstructionException();

        // Topmost bits, i.e. csr[11:8], encode access rights for CSR by convention. Of these, the top-most two bits,
        // csr[11:10], encode read-only state, where 0b11: read-only, 0b00..0b10: read-write.
        if (throwIfReadonly && ((csr & 0b1100_0000_0000) == 0b1100_0000_0000))
            throw new R5IllegalInstructionException();
        // The two following bits, csr[9:8], encode the lowest privilege level that can access the CSR.
        if (priv < ((csr >>> 8) & 0b11))
            throw new R5IllegalInstructionException();
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private long readCSR(final int csr) throws R5IllegalInstructionException {
        switch (csr) {
            // Floating-Point Control and Status Registers
            case 0x001 -> { // fflags, Floating-Point Accrued Exceptions.
                checkFPUEnabled();
                return fpu32.flags.value;
            }
            case 0x002 -> { // frm, Floating-Point Dynamic Rounding Mode.
                checkFPUEnabled();
                return frm;
            }
            case 0x003 -> { // fcsr, Floating-Point Control and Status Register (frm + fflags).
                checkFPUEnabled();
                return (frm << 5) | fpu32.flags.value;
            }

            // User Trap Setup
            // 0x000: ustatus, User status register.
            // 0x004: uie, User interrupt-enabled register.
            // 0x005: utvec, User trap handler base address.

            // User Trap Handling
            // 0x040: uscratch, Scratch register for user trap handlers.
            // 0x041: uepc, User exception program counter.
            // 0x042: ucause, User trap cause.
            // 0x043: utval, User bad address or instruction.
            // 0x044: uip, User interrupt pending.

            // Supervisor Trap Setup
            case 0x100 -> { // sstatus, Supervisor status register.
                return getStatus(getSupervisorStatusMask());
            }
            // 0x102: sedeleg, Supervisor exception delegation register.
            // 0x103: sideleg, Supervisor interrupt delegation register.
            case 0x104 -> { // sie, Supervisor interrupt-enable register.
                return mie & mideleg; // Effectively read-only because we don't implement N.
            }
            case 0x105 -> { // stvec, Supervisor trap handler base address.
                return stvec;
            }
            case 0x106 -> { // scounteren, Supervisor counter enable.
                return scounteren;
            }

            // Supervisor Trap Handling
            case 0x140 -> { // sscratch Scratch register for supervisor trap handlers.
                return sscratch;
            }
            case 0x141 -> { // sepc Supervisor exception program counter.
                return sepc;
            }
            case 0x142 -> { // scause Supervisor trap cause.
                return scause;
            }
            case 0x143 -> { // stval Supervisor bad address or instruction.
                return stval;
            }
            case 0x144 -> { // sip Supervisor interrupt pending.
                return mip.get() & mideleg; // Effectively read-only because we don't implement N.
            }

            // Supervisor Protection and Translation
            case 0x180 -> { // satp Supervisor address translation and protection.
                if (priv == R5.PRIVILEGE_S && (mstatus & R5.STATUS_TVM_MASK) != 0) {
                    throw new R5IllegalInstructionException();
                }
                return satp;
            }

            // Virtual Supervisor Registers
            // 0x200: vsstatus, Virtual supervisor status register.
            // 0x204: vsie, Virtual supervisor interrupt-enable register.
            // 0x205: vstvec, Virtual supervisor trap handler base address.
            // 0x240: vsscratch, Virtual supervisor scratch register.
            // 0x241: vsepc, Virtual supervisor exception program counter.
            // 0x242: vscause, Virtual supervisor trap cause.
            // 0x243: vstval, Virtual supervisor bad address or instruction.
            // 0x244: vsip, Virtual supervisor interrupt pending.
            // 0x280: vsatp, Virtual supervisor address translation and protection

            // Machine Trap Setup
            case 0x300 -> { // mstatus Machine status register.
                return getStatus(MSTATUS_MASK);
            }
            case 0x301 -> { // misa ISA and extensions
                return misa();
            }
            case 0x302 -> { // medeleg Machine exception delegation register.
                return medeleg;
            }
            case 0x303 -> { // mideleg Machine interrupt delegation register.
                return mideleg;
            }
            case 0x304 -> { // mie Machine interrupt-enable register.
                return mie;
            }
            case 0x305 -> { // mtvec Machine trap-handler base address.
                return mtvec;
            }
            case 0x306 -> { // mcounteren Machine counter enable.
                return mcounteren;
            }
            case 0x310 -> { // mstatush, Additional machine status register, RV32 only.
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();
                return getStatus(MSTATUS_MASK) >>> 32;
            }

            // Debug/Trace Registers
            case 0x7A0 -> { // tselect
                return 0;
            }
            case 0x7A1 -> { // tdata1
                return 0;
            }
            case 0x7A2 -> { // tdata2
                return 0;
            }
            case 0x7A3 -> { // tdata3
                return 0;
            }

            // Machine Trap Handling
            case 0x340 -> { // mscratch Scratch register for machine trap handlers.
                return mscratch;
            }
            case 0x341 -> { // mepc Machine exception program counter.
                return mepc;
            }
            case 0x342 -> { // mcause Machine trap cause.
                return mcause;
            }
            case 0x343 -> { // mtval Machine bad address or instruction.
                return mtval;
            }
            case 0x344 -> { // mip Machine interrupt pending.
                return mip.get();
            }
            // 0x34A: mtinst, Machine trap instruction (transformed).
            // 0x34B: mtval2, Machine bad guest physical address.

            // Machine Memory Protection
            // 0x3A0: pmpcfg0. Physical memory protection configuration.
            // 0x3A1: pmpcfg1. Physical memory protection configuration, RV32 only.
            // 0x3A2: pmpcfg2. Physical memory protection configuration.
            // 0x3A3...0x3AE: pmpcfg3...pmpcfg14, Physical memory protection configuration, RV32 only.
            // 0x3AF: pmpcfg15, Physical memory protection configuration, RV32 only.
            // 0x3B0: pmpaddr0, Physical memory protection address register.
            // 0x3B1...0x3EF: pmpaddr1...pmpaddr63, Physical memory protection address register.

            // Hypervisor Trap Setup
            // 0x600: hstatus, Hypervisor status register.
            // 0x602: hedeleg, Hypervisor exception delegation register.
            // 0x603: hideleg, Hypervisor interrupt delegation register.
            // 0x604: hie, Hypervisor interrupt-enable register.
            // 0x606: hcounteren, Hypervisor counter enable.
            // 0x607: hgeie, Hypervisor guest external interrupt-enable register.

            // Hypervisor Trap Handling
            // 0x643: htval, Hypervisor bad guest physical address.
            // 0x644: hip, Hypervisor interrupt pending.
            // 0x645: hvip, Hypervisor virtual interrupt pending.
            // 0x64A: htinst, Hypervisor trap instruction (transformed).
            // 0xE12: hgeip, Hypervisor guest external interrupt pending.

            // Hypervisor Protection and Translation
            // 0x680: hgatp, Hypervisor guest address translation and protection.

            // Hypervisor Counter/Timer Virtualization Registers
            // 0x605: htimedelta, Delta for VS/VU-mode timer.
            // 0x615: htimedeltah, Upper 32 bits of htimedelta, RV32 only.

            //Machine Counter/Timers
            case 0xB00 -> { // mcycle, Machine cycle counter.
                return mcycle;
            }
            case 0xB02 -> { // minstret, Machine instructions-retired counter.
                return minstret;
            }
            // 0xB03: mhpmcounter3, Machine performance-monitoring counter.
            // 0xB04...0xB1F: mhpmcounter4...mhpmcounter31, Machine performance-monitoring counter.
            case 0xB80 -> { // mcycleh, Upper 32 bits of mcycle, RV32 only.
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();
                return mcycle >>> 32;
            }
            case 0xB82 -> { // minstreth, Upper 32 bits of minstret, RV32 only.
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();
                return minstret >>> 32;
            }
            // 0xB83: mhpmcounter3h, Upper 32 bits of mhpmcounter3, RV32 only.
            // 0xB84...0xB9F: mhpmcounter4h...mhpmcounter31h, Upper 32 bits of mhpmcounter4, RV32 only.

            // Counters and Timers
            case 0xC00 -> { // cycle
                // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                checkCounterAccess(csr & 0b11);
                return mcycle;
            }
            case 0xC01 -> { // time
                return rtc.getTime();
            }
            case 0xC02 -> { // instret
                // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                checkCounterAccess(csr & 0b11);
                return minstret;
            }
            // 0xC03 ... 0xC1F: hpmcounter3 ... hpmcounter31
            case 0xC80 -> { // cycleh
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();

                // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                checkCounterAccess(csr & 0b11);
                return mcycle >>> 32;
            }
            case 0xC82 -> { // instreth
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();

                // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                checkCounterAccess(csr & 0b11);
                return minstret >>> 32;
            }
            // 0xC81: timeh
            // 0xC83 ... 0xC9F: hpmcounter3h ... hpmcounter31h

            // Machine Information Registers
            case 0xF11 -> { // mvendorid, Vendor ID.
                return 0; // Not implemented.
            }
            case 0xF12 -> { // marchid, Architecture ID.
                return 0; // Not implemented.
            }
            case 0xF13 -> { // mimpid, Implementation ID.
                return 0; // Not implemented.
            }
            case 0xF14 -> { // mhartid, Hardware thread ID.
                return 0; // Single, primary hart.
            }
            default -> throw new R5IllegalInstructionException();
        }
    }

    private boolean writeCSR(final int csr, final long value) throws R5IllegalInstructionException {
        switch (csr) {
            // Floating-Point Control and Status Registers
            case 0x001 -> { // fflags, Floating-Point Accrued Exceptions.
                checkFPUEnabled();
                fpu32.flags.value = (byte) (value & 0b11111);
                fs = R5.FS_DIRTY;
            }
            case 0x002 -> { // frm, Floating-Point Dynamic Rounding Mode.
                checkFPUEnabled();
                frm = (byte) (value & 0b111);
                fs = R5.FS_DIRTY;
            }
            case 0x003 -> { // fcsr, Floating-Point Control and Status Register (frm + fflags).
                checkFPUEnabled();
                frm = (byte) ((value >>> 5) & 0b111);
                fpu32.flags.value = (byte) (value & 0b11111);
                fs = R5.FS_DIRTY;
            }

            // User Trap Setup
            // 0x000: ustatus, User status register.
            // 0x004: uie, User interrupt-enabled register.
            // 0x005: utvec, User trap handler base address.

            // User Trap Handling
            // 0x040: uscratch, Scratch register for user trap handlers.
            // 0x041: uepc, User exception program counter.
            // 0x042: ucause, User trap cause.
            // 0x043: utval, User bad address or instruction.
            // 0x044: uip, User interrupt pending.

            // Supervisor Trap Setup
            case 0x100 -> { // sstatus, Supervisor status register.
                final long supervisorStatusMask = getSupervisorStatusMask();
                setStatus((mstatus & ~supervisorStatusMask) | (value & supervisorStatusMask));
            }
            // 0x102: sedeleg, Supervisor exception delegation register.
            // 0x103: sideleg, Supervisor interrupt delegation register.
            case 0x104 -> { // sie, Supervisor interrupt-enable register.
                final long mask = mideleg; // Can only set stuff that's delegated to S mode.
                mie = (mie & ~mask) | (value & mask);
            }
            case 0x105 -> { // stvec, Supervisor trap handler base address.
                if ((value & 0b11) < 2) { // Don't allow reserved modes.
                    stvec = value;
                }
            }
            case 0x106 -> // scounteren, Supervisor counter enable.
                scounteren = (int) (value & COUNTEREN_MASK);

            // Supervisor Trap Handling
            case 0x140 -> // sscratch Scratch register for supervisor trap handlers.
                sscratch = value;
            case 0x141 -> // sepc Supervisor exception program counter.
                sepc = value & ~0b1;
            case 0x142 -> // scause Supervisor trap cause.
                scause = value;
            case 0x143 -> // stval Supervisor bad address or instruction.
                stval = value;
            case 0x144 -> { // sip Supervisor interrupt pending.
                final long mask = mideleg; // Can only set stuff that's delegated to S mode.
                mip.updateAndGet(operand -> (operand & ~mask) | (value & mask));
            }

            // Supervisor Protection and Translation
            case 0x180 -> { // satp Supervisor address translation and protection.
                // Say no to ASID (not implemented).
                final long validatedValue;
                if (xlen == R5.XLEN_32) {
                    validatedValue = value & ~R5.SATP_ASID_MASK32;
                } else {
                    validatedValue = value & ~R5.SATP_ASID_MASK64;
                }

                final long change = satp ^ validatedValue;
                if (change != 0) {
                    if (priv == R5.PRIVILEGE_S && (mstatus & R5.STATUS_TVM_MASK) != 0) {
                        throw new R5IllegalInstructionException();
                    }

                    if (xlen != R5.XLEN_32) {
                        // We only support Sv39 and Sv48. On unsupported writes spec says just don't change anything.
                        final long mode = validatedValue & R5.SATP_MODE_MASK64;
                        if (mode != R5.SATP_MODE_NONE && mode != R5.SATP_MODE_SV39 && mode != R5.SATP_MODE_SV48) {
                            break;
                        }
                    }

                    // From RISC-V Privileged Architectures, section 4.2.1
                    // "Changing satp.MODE from Bare to other modes and vice versa also takes effect immediately,
                    // without the need to execute an SFENCE.VMA instruction."
                    if (xlen == R5.XLEN_32) {
                        if (((satp & R5.SATP_MODE_MASK32) == R5.SATP_MODE_NONE) !=
                            ((validatedValue & R5.SATP_MODE_MASK32) == R5.SATP_MODE_NONE)) {
                            flushTLB();
                        }
                    } else {
                        if (((satp & R5.SATP_MODE_MASK64) == R5.SATP_MODE_NONE) !=
                            ((validatedValue & R5.SATP_MODE_MASK64) == R5.SATP_MODE_NONE)) {
                            flushTLB();
                        }
                    }

                    satp = validatedValue;

                    return true; // Invalidate fetch cache.
                }
            }

            // Virtual Supervisor Registers
            // 0x200: vsstatus, Virtual supervisor status register.
            // 0x204: vsie, Virtual supervisor interrupt-enable register.
            // 0x205: vstvec, Virtual supervisor trap handler base address.
            // 0x240: vsscratch, Virtual supervisor scratch register.
            // 0x241: vsepc, Virtual supervisor exception program counter.
            // 0x242: vscause, Virtual supervisor trap cause.
            // 0x243: vstval, Virtual supervisor bad address or instruction.
            // 0x244: vsip, Virtual supervisor interrupt pending.
            // 0x280: vsatp, Virtual supervisor address translation and protection

            // Machine Trap Setup
            case 0x300 -> // mstatus Machine status register.
                setStatus(value & MSTATUS_MASK);
            case 0x301 -> { // misa ISA and extensions
                // We do not support changing feature sets dynamically.
            }
            case 0x302 -> // medeleg Machine exception delegation register.
                // From Volume 2 p31: For exceptions that cannot occur in less privileged modes, the corresponding
                // medeleg bits should be hardwired to zero. In particular, medeleg[11] is hardwired to zero.
                medeleg = value & ~(1 << R5.EXCEPTION_MACHINE_ECALL);
            case 0x303 -> { // mideleg Machine interrupt delegation register.
                final int mask = R5.SSIP_MASK | R5.STIP_MASK | R5.SEIP_MASK;
                mideleg = (mideleg & ~mask) | (value & mask);
            }
            case 0x304 -> { // mie Machine interrupt-enable register.
                final int mask = R5.MTIP_MASK | R5.MSIP_MASK | R5.SEIP_MASK | R5.STIP_MASK | R5.SSIP_MASK;
                mie = (mie & ~mask) | (value & mask);
            }
            case 0x305 -> { // mtvec Machine trap-handler base address.
                if ((value & 0b11) < 2) { // Don't allow reserved modes.
                    mtvec = value;
                }
            }
            case 0x306 -> // mcounteren Machine counter enable.
                mcounteren = (int) (value & COUNTEREN_MASK);
            case 0x310 -> { // mstatush Additional machine status register, RV32 only.
                if (xlen != R5.XLEN_32) throw new R5IllegalInstructionException();
                setStatus((value << 32) & MSTATUS_MASK);
            }

            // Debug/Trace Registers
            case 0x7A0 -> { // tselect
            }
            case 0x7A1 -> { // tdata1
            }
            case 0x7A2 -> { // tdata2
            }
            case 0x7A3 -> { // tdata3
            }

            // Machine Trap Handling
            case 0x340 -> // mscratch Scratch register for machine trap handlers.
                mscratch = value;
            case 0x341 -> // mepc Machine exception program counter.
                mepc = value & ~0b1; // p38: Lowest bit must always be zero.
            case 0x342 -> // mcause Machine trap cause.
                mcause = value;
            case 0x343 -> // mtval Machine bad address or instruction.
                mtval = value;
            case 0x344 -> { // mip Machine interrupt pending.
                // p32: MEIP, MTIP, MSIP are readonly in mip.
                // Additionally, SEIP is controlled by a PLIC in our case, so we must not allow
                // software to reset it, as this could lead to lost interrupts.
                final int mask = R5.STIP_MASK | R5.SSIP_MASK;
                mip.updateAndGet(operand -> (operand & ~mask) | (value & mask));
            }
            // 0x34A: mtinst, Machine trap instruction (transformed).
            // 0x34B: mtval2, Machine bad guest physical address.

            // Machine Memory Protection
            // 0x3A0: pmpcfg0. Physical memory protection configuration.
            // 0x3A1: pmpcfg1. Physical memory protection configuration, RV32 only.
            // 0x3A2: pmpcfg2. Physical memory protection configuration.
            // 0x3A3...0x3AE: pmpcfg3...pmpcfg14, Physical memory protection configuration, RV32 only.
            // 0x3AF: pmpcfg15, Physical memory protection configuration, RV32 only.
            // 0x3B0: pmpaddr0, Physical memory protection address register.
            // 0x3B1...0x3EF: pmpaddr1...pmpaddr63, Physical memory protection address register.

            // Hypervisor Trap Setup
            // 0x600: hstatus, Hypervisor status register.
            // 0x602: hedeleg, Hypervisor exception delegation register.
            // 0x603: hideleg, Hypervisor interrupt delegation register.
            // 0x604: hie, Hypervisor interrupt-enable register.
            // 0x606: hcounteren, Hypervisor counter enable.
            // 0x607: hgeie, Hypervisor guest external interrupt-enable register.

            // Hypervisor Trap Handling
            // 0x643: htval, Hypervisor bad guest physical address.
            // 0x644: hip, Hypervisor interrupt pending.
            // 0x645: hvip, Hypervisor virtual interrupt pending.
            // 0x64A: htinst, Hypervisor trap instruction (transformed).

            // Hypervisor Protection and Translation
            // 0x680: hgatp, Hypervisor guest address translation and protection.

            // Hypervisor Counter/Timer Virtualization Registers
            // 0x605: htimedelta, Delta for VS/VU-mode timer.
            // 0x615: htimedeltah, Upper 32 bits of htimedelta, RV32 only.

            // Sedna proprietary CSRs.
            case 0xBC0 -> { // Switch to 32 bit XLEN.
                // This CSR exists purely to allow switching the CPU to 32 bit mode from programs
                // that were compiled for 32 bit. Since those cannot set the MXL bits of the misa
                // CSR when the machine is currently in 64 bit mode.
                setXLEN(R5.XLEN_32);
                return true;
            }
            default -> throw new R5IllegalInstructionException();
        }

        return false;
    }

    private void checkCounterAccess(final int bit) throws R5IllegalInstructionException {
        // See Volume 2 p36: mcounteren/scounteren define availability to next lowest privilege level.
        if (priv < R5.PRIVILEGE_M) {
            final int counteren;
            if (priv < R5.PRIVILEGE_S) {
                counteren = scounteren;
            } else {
                counteren = mcounteren;
            }

            if ((counteren & (1 << bit)) == 0) {
                throw new R5IllegalInstructionException();
            }
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Misc

    private long getStatus(final long mask) {
        final long status = (mstatus | (fs << R5.STATUS_FS_SHIFT)) & mask;
        final boolean dirty = ((status & R5.STATUS_FS_MASK) == R5.STATUS_FS_MASK) ||
            ((status & R5.STATUS_XS_MASK) == R5.STATUS_XS_MASK);
        return status | (dirty ? R5.getStatusStateDirtyMask(xlen) : 0);
    }

    private void setStatus(final long value) {
        final long change = mstatus ^ value;

        // SUM and MXR change what a translation resolves to at an unchanged privilege level, so
        // cached entries really are stale and have to go. MPRV and MPP only change which privilege
        // level data accesses run at, which the tag already distinguishes, so those just need the
        // tag recomputed below.
        if ((change & (R5.STATUS_SUM_MASK | R5.STATUS_MXR_MASK)) != 0) {
            flushTLB();
        }

        fs = (byte) ((value & R5.STATUS_FS_MASK) >> R5.STATUS_FS_SHIFT);

        final long mask = MSTATUS_MASK & ~(R5.getStatusStateDirtyMask(xlen) | R5.STATUS_FS_MASK |
            R5.STATUS_UXL_MASK | R5.STATUS_SXL_MASK);
        mstatus = (mstatus & ~mask) | (value & mask);

        updateTLBTags();
    }

    private void setPrivilege(final int level) {
        // Always update, MPRV may have changed.
        updateTLBTags();

        if (priv == level) {
            return;
        }

        switch (level) {
            case R5.PRIVILEGE_S -> xlen = R5.xlen((mstatus & R5.STATUS_SXL_MASK) >>> R5.STATUS_SXL_SHIFT);
            case R5.PRIVILEGE_U -> xlen = R5.xlen((mstatus & R5.STATUS_UXL_MASK) >>> R5.STATUS_UXL_SHIFT);
            default -> xlen = R5.xlen(mxl);
        }

        priv = level;

        // No TLB flush: entries carry the privilege they were made under, so the ones belonging to
        // the level being left simply stop matching, and are still there when it is returned to.
        updateTLBTags();
    }

    private void updateTLBTags() {
        fetchTLBTag = priv;
        dataTLBTag = (mstatus & R5.STATUS_MPRV_MASK) != 0
            ? (mstatus & R5.STATUS_MPP_MASK) >>> R5.STATUS_MPP_SHIFT
            : priv;
    }

    private void checkFPUEnabled() throws R5IllegalInstructionException {
        // Spec says "when an extension’s status is set to Off, any instruction that attempts
        // to read or write the corresponding state will cause an illegal instruction exception."
        if (fs == R5.FS_OFF) {
            throw new R5IllegalInstructionException();
        }
    }

    private int resolveRoundingMode(int rm) throws R5IllegalInstructionException {
        if (rm == R5.FCSR_FRM_DYN) {
            rm = frm;
        }
        if (rm > R5.FCSR_FRM_RMM) {
            throw new R5IllegalInstructionException();
        }
        return rm;
    }

    ///////////////////////////////////////////////////////////////////
    // Exceptions

    protected void raiseException(final long exception, final long value) {
        // Exceptions take cycle.
        mcycle++;

        // Check whether to run supervisor level trap instead of machine level one.
        // We don't implement the N extension (user level interrupts) so if we're
        // currently in S or U privilege level we'll run the S level trap handler
        // either way -- assuming that the current interrupt/exception is allowed
        // to be delegated by M level.
        final long interruptMask = R5.interrupt(xlen);
        final boolean async = (exception & interruptMask) != 0;
        final long cause = exception & ~interruptMask;
        final long deleg = async ? mideleg : medeleg;

        // xPIE holds the interrupt-enable bit of the *target* mode from before the trap; the
        // interrupted mode's privilege goes into xPP. Note we can get here even for interrupts
        // whose global enable is off: an M level interrupt raised while in S mode is taken
        // regardless of the M level interrupt enabled bit, as per spec (Volume 2 p21).
        final long vec;
        if (priv <= R5.PRIVILEGE_S && ((deleg >>> cause) & 0b1) != 0) {
            scause = exception;
            sepc = pc;
            stval = value;
            mstatus = (mstatus & ~R5.STATUS_SPIE_MASK) |
                (((mstatus & R5.STATUS_SIE_MASK) >>> R5.STATUS_SIE_SHIFT) << R5.STATUS_SPIE_SHIFT);
            mstatus = (mstatus & ~R5.STATUS_SPP_MASK) |
                (((long) priv) << R5.STATUS_SPP_SHIFT);
            mstatus &= ~R5.STATUS_SIE_MASK;
            setPrivilege(R5.PRIVILEGE_S);
            vec = stvec;
        } else {
            mcause = exception;
            mepc = pc;
            mtval = value;
            mstatus = (mstatus & ~R5.STATUS_MPIE_MASK) |
                (((mstatus & R5.STATUS_MIE_MASK) >>> R5.STATUS_MIE_SHIFT) << R5.STATUS_MPIE_SHIFT);
            mstatus = (mstatus & ~R5.STATUS_MPP_MASK) |
                (((long) priv) << R5.STATUS_MPP_SHIFT);
            mstatus &= ~R5.STATUS_MIE_MASK;
            setPrivilege(R5.PRIVILEGE_M);
            vec = mtvec;
        }

        final int mode = (int) vec & 0b11;
        switch (mode) {
            case 0b01: { // Vectored
                if (async) {
                    pc = (vec & ~0b1) + 4 * cause;
                } else {
                    pc = vec & ~0b1;
                }
                break;
            }
            case 0b00: // Direct
            default: {
                pc = vec;
                break;
            }
        }
    }

    private void raiseException(final long cause) {
        raiseException(cause, 0);
    }

    private void raiseInterrupt(final long pending) {
        final boolean mieEnabled = (mstatus & R5.STATUS_MIE_MASK) != 0;
        final boolean sieEnabled = (mstatus & R5.STATUS_SIE_MASK) != 0;

        // V2p21: interrupts handled by a higher privilege level, i.e. that are not delegated
        // to a lower privilege level, will always fire -- even if their global flag is false!
        final long mieMask = priv < R5.PRIVILEGE_M || mieEnabled ? -1L : 0;
        final long sieMask = priv < R5.PRIVILEGE_S || (priv == R5.PRIVILEGE_S && sieEnabled) ? -1L : 0;

        final long interrupts = (pending & ~mideleg & mieMask) | (pending & mideleg & sieMask);
        if (interrupts != 0) {
            // p33: Interrupt order is handled in decreasing order of privilege mode,
            // and inside a single privilege mode in order E,S,T.
            // Custom interrupts have highest priority and are processed low to high.
            final long customInterrupts = interrupts >>> (R5.MEIP_SHIFT + 1);
            if (customInterrupts != 0) {
                final int interrupt = Long.numberOfTrailingZeros(customInterrupts) + R5.MEIP_SHIFT + 1;
                raiseException(interrupt | R5.interrupt(xlen));
            } else if ((pending & R5.MEIP_MASK) != 0) {
                raiseException(R5.MEIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.MSIP_MASK) != 0) {
                raiseException(R5.MSIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.MTIP_MASK) != 0) {
                raiseException(R5.MTIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.SEIP_MASK) != 0) {
                raiseException(R5.SEIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.SSIP_MASK) != 0) {
                raiseException(R5.SSIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.STIP_MASK) != 0) {
                raiseException(R5.STIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.UEIP_MASK) != 0) {
                raiseException(R5.UEIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.USIP_MASK) != 0) {
                raiseException(R5.USIP_SHIFT | R5.interrupt(xlen));
            } else if ((pending & R5.UTIP_MASK) != 0) {
                raiseException(R5.UTIP_SHIFT | R5.interrupt(xlen));
            }
        }
    }

    ///////////////////////////////////////////////////////////////////
    // MMU

    private int fetchPage(final long address) throws R5MemoryAccessException {
        if ((address & 1) != 0) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_MISALIGNED_FETCH);
        }

        final int index = tlbIndex(address);
        final long hash = (address & ~R5.PAGE_ADDRESS_MASK) | fetchTLBTag;
        if (fetchTLBHash[index] == hash) {
            return index;
        } else {
            return fetchPageSlow(address);
        }
    }

    private byte load8(final long address) throws R5MemoryAccessException {
        return (byte) loadx(address, Sizes.SIZE_8, Sizes.SIZE_8_LOG2);
    }

    private void store8(final long address, final byte value) throws R5MemoryAccessException {
        storex(address, value, Sizes.SIZE_8, Sizes.SIZE_8_LOG2);
    }

    private short load16(final long address) throws R5MemoryAccessException {
        return (short) loadx(address, Sizes.SIZE_16, Sizes.SIZE_16_LOG2);
    }

    private void store16(final long address, final short value) throws R5MemoryAccessException {
        storex(address, value, Sizes.SIZE_16, Sizes.SIZE_16_LOG2);
    }

    private int load32(final long address) throws R5MemoryAccessException {
        return (int) loadx(address, Sizes.SIZE_32, Sizes.SIZE_32_LOG2);
    }

    private void store32(final long address, final int value) throws R5MemoryAccessException {
        storex(address, value, Sizes.SIZE_32, Sizes.SIZE_32_LOG2);
    }

    private long load64(final long address) throws R5MemoryAccessException {
        return loadx(address, Sizes.SIZE_64, Sizes.SIZE_64_LOG2);
    }

    private void store64(final long address, final long value) throws R5MemoryAccessException {
        storex(address, value, Sizes.SIZE_64, Sizes.SIZE_64_LOG2);
    }

    private long loadx(final long address, final int size, final int sizeLog2) throws R5MemoryAccessException {
        final long lastAddress = address + size / 8 - 1;
        if ((address & ~R5.PAGE_ADDRESS_MASK) != (lastAddress & ~R5.PAGE_ADDRESS_MASK)) {
            return loadxPageMisaligned(address, size);
        }

        final int index = tlbIndex(address);
        final long hash = (address & ~R5.PAGE_ADDRESS_MASK) | dataTLBTag;
        if (loadTLBHash[index] == hash) {
            try {
                return loadTLBDevice[index].load((int) (address + loadTLBToOffset[index]), sizeLog2);
            } catch (final MemoryAccessException e) {
                throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_LOAD);
            }
        } else {
            return loadSlow(address, sizeLog2);
        }
    }

    private void storex(final long address, final long value, final int size, final int sizeLog2) throws R5MemoryAccessException {
        final long lastAddress = address + size / 8 - 1;
        if ((address & ~R5.PAGE_ADDRESS_MASK) != (lastAddress & ~R5.PAGE_ADDRESS_MASK)) {
            storexPageMisaligned(address, value, size);
            return;
        }

        final int index = tlbIndex(address);
        final long hash = (address & ~R5.PAGE_ADDRESS_MASK) | dataTLBTag;
        if (storeTLBHash[index] == hash) {
            try {
                storeTLBDevice[index].store((int) (address + storeTLBToOffset[index]), value, sizeLog2);
            } catch (final MemoryAccessException e) {
                throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_STORE);
            }
        } else {
            storeSlow(address, value, sizeLog2);
        }
    }

    private int fetchPageSlow(final long address) throws R5MemoryAccessException {
        final long physicalAddress = getPhysicalAddress(address, MemoryAccessType.FETCH, false);
        final MappedMemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
        if (range == null || !range.device.supportsFetch()) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_FETCH);
        }
        final int index = installTLB(fetchTLBHash, fetchTLBToOffset, fetchTLBDevice, address, fetchTLBTag, physicalAddress, range);
        if (debugInterface.breakpoints.isEmpty()) {
            fetchTLBBreakpoints[index] = null;
        } else {
            final var subset = debugInterface.breakpoints.subSet(address, address + (1 << R5.PAGE_ADDRESS_SHIFT));
            if (subset.isEmpty()) {
                fetchTLBBreakpoints[index] = null;
            } else {
                final LongSet pageBreakpoints = new LongOpenHashSet(subset.size());
                pageBreakpoints.addAll(subset);
                fetchTLBBreakpoints[index] = pageBreakpoints;
            }
        }
        return index;
    }

    private long loadSlow(final long address, final int sizeLog2) throws R5MemoryAccessException {
        final MMIOTLBEntry mmioEntry = mmioLoadTLB[mmioIndex(address)];
        if (mmioEntry.matches(address, dataTLBTag, sizeLog2)) {
            try {
                return mmioEntry.device.load((int) (address + mmioEntry.toOffset), sizeLog2);
            } catch (final MemoryAccessException e) {
                throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_LOAD);
            }
        }

        final long physicalAddress = getPhysicalAddress(address, MemoryAccessType.LOAD, false);
        final MappedMemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
        if (range == null) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_LOAD);
        }

        try {
            if (range.device.supportsFetch()) {
                installTLB(loadTLBHash, loadTLBToOffset, loadTLBDevice, address, dataTLBTag, physicalAddress, range);
                return range.device.load((int) (physicalAddress - range.start), sizeLog2);
            } else {
                updateMMIOTLB(mmioEntry, address, dataTLBTag, physicalAddress, range);
                return range.device.load((int) (physicalAddress - range.address()), sizeLog2);
            }
        } catch (final MemoryAccessException e) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_LOAD);
        }
    }

    private void storeSlow(final long address, final long value, final int sizeLog2) throws R5MemoryAccessException {
        final MMIOTLBEntry mmioEntry = mmioStoreTLB[mmioIndex(address)];
        if (mmioEntry.matches(address, dataTLBTag, sizeLog2)) {
            try {
                mmioEntry.device.store((int) (address + mmioEntry.toOffset), value, sizeLog2);
                return;
            } catch (final MemoryAccessException e) {
                throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_STORE);
            }
        }

        final long physicalAddress = getPhysicalAddress(address, MemoryAccessType.STORE, false);
        final MappedMemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
        if (range == null) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_STORE);
        }

        try {
            if (range.device.supportsFetch()) {
                installTLB(storeTLBHash, storeTLBToOffset, storeTLBDevice, address, dataTLBTag, physicalAddress, range);
                final int offset = (int) (physicalAddress - range.start);
                range.device.store(offset, value, sizeLog2);
                physicalMemory.setDirty(range, offset);
            } else {
                updateMMIOTLB(mmioEntry, address, dataTLBTag, physicalAddress, range);
                range.device.store((int) (physicalAddress - range.start), value, sizeLog2);
            }
        } catch (final MemoryAccessException e) {
            throw new R5MemoryAccessException(address, R5.EXCEPTION_FAULT_STORE);
        }
    }

    private long loadxPageMisaligned(final long address, final int size) throws R5MemoryAccessException {
        long value = 0;
        for (int i = 0; i < size / 8; i++) {
            value |= (loadx(address + i, 8, 0) & 0xFF) << (i * 8);
        }
        return value;
    }

    private void storexPageMisaligned(final long address, final long value, final int size) throws R5MemoryAccessException {
        for (int i = 0; i < size / 8; i++) {
            final long valueByte = value >> i * 8 & 0xFF;
            storex(address + i, valueByte, 8, 0);
        }
    }

    private long getPhysicalAddress(final long virtualAddress, final MemoryAccessType accessType, final boolean bypassPermissions) throws R5MemoryAccessException {
        final int privilege;
        if ((mstatus & R5.STATUS_MPRV_MASK) != 0 && accessType != MemoryAccessType.FETCH) {
            privilege = (int) ((mstatus & R5.STATUS_MPP_MASK) >>> R5.STATUS_MPP_SHIFT);
        } else {
            privilege = this.priv;
        }

        if (privilege == R5.PRIVILEGE_M) {
            if (xlen == R5.XLEN_32) {
                return virtualAddress & 0xFFFFFFFFL;
            } else {
                return virtualAddress;
            }
        }

        final long mode;
        if (xlen == R5.XLEN_32) {
            mode = satp & R5.SATP_MODE_MASK32;
            if (mode == R5.SATP_MODE_NONE) {
                return virtualAddress & 0xFFFFFFFFL;
            }
        } else {
            mode = satp & R5.SATP_MODE_MASK64;
            if (mode == R5.SATP_MODE_NONE) {
                return virtualAddress;
            }
        }

        final int levels, pteSizeLog2;
        final long ppnMask;
        if (mode == R5.SATP_MODE_SV32) {
            levels = R5.SV32_LEVELS;
            ppnMask = R5.SATP_PPN_MASK32;
            pteSizeLog2 = Sizes.SIZE_32_LOG2;
        } else if (mode == R5.SATP_MODE_SV39) {
            levels = R5.SV39_LEVELS;
            ppnMask = R5.SATP_PPN_MASK64;
            pteSizeLog2 = Sizes.SIZE_64_LOG2;
        } else if (mode == R5.SATP_MODE_SV48) {
            levels = R5.SV48_LEVELS;
            ppnMask = R5.SATP_PPN_MASK64;
            pteSizeLog2 = Sizes.SIZE_64_LOG2;
        } else if (mode == R5.SATP_MODE_SV57) {
            levels = R5.SV57_LEVELS;
            ppnMask = R5.SATP_PPN_MASK64;
            pteSizeLog2 = Sizes.SIZE_64_LOG2;
        } else {
            throw getPageFaultException(accessType, virtualAddress);
        }

        final int xpnSize = R5.PAGE_ADDRESS_SHIFT - pteSizeLog2;
        final int xpnMask = (1 << xpnSize) - 1;

        // V2, 4.5.1:
        // "Instruction fetch addresses and load and store effective addresses, which are 64 bits,
        // must have bits 63–48 all equal to bit 47, or else a page-fault exception will occur."
        if (xlen == R5.XLEN_64) {
            final int shift = Long.SIZE - (levels * xpnSize + R5.PAGE_ADDRESS_SHIFT);
            if ((virtualAddress << shift >> shift) != virtualAddress) {
                throw getPageFaultException(accessType, virtualAddress);
            }
        }

        // Virtual address translation, V2p75f.
        long pteAddress = (satp & ppnMask) << R5.PAGE_ADDRESS_SHIFT; // 1.
        for (int i = levels - 1; i >= 0; i--) {
            final int vpnShift = R5.PAGE_ADDRESS_SHIFT + xpnSize * i;
            final int vpn = (int) ((virtualAddress >>> vpnShift) & xpnMask);
            pteAddress += ((long) vpn) << pteSizeLog2; // equivalent to vpn * PTE size

            long pte;
            try {
                pte = physicalMemory.load(pteAddress, pteSizeLog2); // 2.
            } catch (final MemoryAccessException e) {
                pte = 0;
            }

            if ((pte & R5.PTE_V_MASK) == 0 || ((pte & R5.PTE_R_MASK) == 0 && (pte & R5.PTE_W_MASK) != 0)) { // 3.
                throw getPageFaultException(accessType, virtualAddress);
            }

            // 4.
            int xwr = (int) (pte & (R5.PTE_X_MASK | R5.PTE_W_MASK | R5.PTE_R_MASK));
            if (xwr == 0) { // r=0 && x=0: pointer to next level of the page table. w=0 is implicit due to r=0 (see 3).
                final long ppn = pte >>> R5.PTE_DATA_BITS;
                pteAddress = ppn << R5.PAGE_ADDRESS_SHIFT;
                continue;
            }

            // 5. Leaf node, do access permission checks.
            if (!bypassPermissions) {
                // Check privilege. Can only be in S or U mode here, M was handled above. V2p61.
                final boolean userModeFlag = (pte & R5.PTE_U_MASK) != 0;
                if (userModeFlag && (privilege != R5.PRIVILEGE_U) &&
                    ((accessType == MemoryAccessType.FETCH) || ((mstatus & R5.STATUS_SUM_MASK) == 0))) {
                    throw getPageFaultException(accessType, virtualAddress);
                }
                if (!userModeFlag && (privilege != R5.PRIVILEGE_S)) {
                    throw getPageFaultException(accessType, virtualAddress);
                }

                // MXR allows read on execute-only pages.
                if (accessType == MemoryAccessType.LOAD && (mstatus & R5.STATUS_MXR_MASK) != 0 && (xwr & R5.PTE_X_MASK) != 0) {
                    xwr |= R5.PTE_R_MASK;
                }

                // Check access flags.
                if ((xwr & accessType.mask) == 0) {
                    throw getPageFaultException(accessType, virtualAddress);
                }
            }
            // 6. Check misaligned superpage.
            if (i > 0) {
                final int ppnLSB = (int) ((pte >>> R5.PTE_DATA_BITS) & xpnMask);
                if (ppnLSB != 0) {
                    throw getPageFaultException(accessType, virtualAddress);
                }
            }

            // 7. Update accessed and dirty flags.
            final long updated_pte = pte | R5.PTE_A_MASK | (accessType == MemoryAccessType.STORE ? R5.PTE_D_MASK : 0);
            if (pte != updated_pte) {
                try {
                    physicalMemory.store(pteAddress, updated_pte, pteSizeLog2);
                } catch (final MemoryAccessException e) {
                    throw getPageFaultException(accessType, virtualAddress);
                }
            }

            // 8. physical address = pte.ppn[LEVELS-1:i], va.vpn[i-1:0], va.pgoff
            final long vpnAndPageOffsetMask = (1L << vpnShift) - 1;
            final long ppn = (pte >>> R5.PTE_DATA_BITS) << R5.PAGE_ADDRESS_SHIFT;
            return (ppn & ~vpnAndPageOffsetMask) | (virtualAddress & vpnAndPageOffsetMask);
        }

        throw getPageFaultException(accessType, virtualAddress);
    }

    private static R5MemoryAccessException getPageFaultException(final MemoryAccessType accessType, final long address) {
        return switch (accessType) {
            case LOAD -> new R5MemoryAccessException(address, R5.EXCEPTION_LOAD_PAGE_FAULT);
            case STORE -> new R5MemoryAccessException(address, R5.EXCEPTION_STORE_PAGE_FAULT);
            case FETCH -> new R5MemoryAccessException(address, R5.EXCEPTION_FETCH_PAGE_FAULT);
        };
    }

    ///////////////////////////////////////////////////////////////////
    // TLB

    private static int tlbIndex(final long address) {
        return (int) ((address >>> R5.PAGE_ADDRESS_SHIFT) & (TLB_SIZE - 1));
    }

    private static int installTLB(final long[] hashes, final long[] toOffsets, final MemoryMappedDevice[] devices,
                                  final long address, final long tag, final long physicalAddress, final MappedMemoryRange range) {
        final int index = tlbIndex(address);
        hashes[index] = (address & ~R5.PAGE_ADDRESS_MASK) | tag;
        toOffsets[index] = physicalAddress - address - range.start;
        devices[index] = range.device;
        return index;
    }

    private static int mmioIndex(final long address) {
        return (int) ((address >>> R5.PAGE_ADDRESS_SHIFT) & (MMIO_TLB_SIZE - 1));
    }

    private static void updateMMIOTLB(final MMIOTLBEntry entry, final long address, final long tag, final long physicalAddress, final MappedMemoryRange range) {
        final long virtualPage = address & ~R5.PAGE_ADDRESS_MASK;
        final long physicalPage = physicalAddress & ~R5.PAGE_ADDRESS_MASK;

        entry.hash = virtualPage | tag;
        entry.toOffset = physicalAddress - address - range.start;
        entry.device = range.device;

        // The device's slice of this page, as virtual addresses. physicalPage <= physicalAddress
        // and range.start <= physicalAddress <= range.end always hold here, so the page-local
        // deltas below cannot underflow.
        final long startInPage = Long.compareUnsigned(range.start, physicalPage) > 0 ? range.start - physicalPage : 0;
        final long endInPage = Math.min(R5.PAGE_ADDRESS_MASK, range.end - physicalPage);
        entry.windowStart = virtualPage + startInPage;
        entry.windowEnd = virtualPage + endInPage;
    }

    private void flushTLB() {
        // Only the hashes: payload slots are never read without a valid hash.
        Arrays.fill(fetchTLBHash, -1);
        Arrays.fill(loadTLBHash, -1);
        Arrays.fill(storeTLBHash, -1);
        for (int i = 0; i < MMIO_TLB_SIZE; i++) {
            mmioLoadTLB[i].hash = -1;
        }
        for (int i = 0; i < MMIO_TLB_SIZE; i++) {
            mmioStoreTLB[i].hash = -1;
        }
    }

    private void flushTLB(final long address) {
        final int index = tlbIndex(address);
        final long hash = address & ~R5.PAGE_ADDRESS_MASK;

        // Compare the address bits only: an entry for this page made under any privilege level is
        // stale once the guest asks for it to be flushed.
        if ((fetchTLBHash[index] & ~R5.PAGE_ADDRESS_MASK) == hash) {
            fetchTLBHash[index] = -1;
        }
        if ((loadTLBHash[index] & ~R5.PAGE_ADDRESS_MASK) == hash) {
            loadTLBHash[index] = -1;
        }
        if ((storeTLBHash[index] & ~R5.PAGE_ADDRESS_MASK) == hash) {
            storeTLBHash[index] = -1;
        }

        final int mmioIndex = mmioIndex(address);
        if ((mmioLoadTLB[mmioIndex].hash & ~R5.PAGE_ADDRESS_MASK) == hash) {
            mmioLoadTLB[mmioIndex].hash = -1;
        }
        if ((mmioStoreTLB[mmioIndex].hash & ~R5.PAGE_ADDRESS_MASK) == hash) {
            mmioStoreTLB[mmioIndex].hash = -1;
        }
    }

    ///////////////////////////////////////////////////////////////////
    // RV32I Base Instruction Set

    @Instruction("LUI")
    protected void lui(@Field("rd") final int rd,
                     @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = imm;
        }
    }

    @Instruction("AUIPC")
    protected void auipc(@Field("rd") final int rd,
                       @Field("imm") final int imm,
                       @ProgramCounter final long pc) {
        if (rd != 0) {
            x[rd] = pc + imm;
        }
    }

    @Branch
    @Instruction("JAL")
    protected void jal(@Field("rd") final int rd,
                     @Field("imm") final int imm,
                     @ProgramCounter final long pc,
                     @InstructionSize final int instructionSize) {
        if (rd != 0) {
            x[rd] = pc + instructionSize;
        }

        this.pc = pc + imm;
    }

    @Branch
    @Instruction("JALR")
    protected void jalr(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm,
                      @ProgramCounter final long pc,
                      @InstructionSize final int instructionSize) {
        // Compute first in case rs1 == rd and clear lowest bit as per spec.
        final long address = (x[rs1] + imm) & ~1;
        if (rd != 0) {
            x[rd] = pc + instructionSize;
        }

        this.pc = address;
    }

    @Branch
    @Instruction("BEQ")
    protected boolean beq(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final long pc) {
        if (x[rs1] == x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Branch
    @Instruction("BNE")
    protected boolean bne(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final long pc) {
        if (x[rs1] != x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Branch
    @Instruction("BLT")
    protected boolean blt(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final long pc) {
        if (x[rs1] < x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Branch
    @Instruction("BGE")
    protected boolean bge(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final long pc) {
        if (x[rs1] >= x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Branch
    @Instruction("BLTU")
    protected boolean bltu(@Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("imm") final int imm,
                         @ProgramCounter final long pc) {
        if (Long.compareUnsigned(x[rs1], x[rs2]) < 0) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Branch
    @Instruction("BGEU")
    protected boolean bgeu(@Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("imm") final int imm,
                         @ProgramCounter final long pc) {
        if (Long.compareUnsigned(x[rs1], x[rs2]) >= 0) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("LB")
    protected void lb(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        final int result = load8(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("LH")
    protected void lh(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        final int result = load16(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("LW")
    protected void lw(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        final int result = load32(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("LBU")
    protected void lbu(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws R5MemoryAccessException {
        final int result = load8(x[rs1] + imm) & 0xFF;
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("LHU")
    protected void lhu(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws R5MemoryAccessException {
        final int result = load16(x[rs1] + imm) & 0xFFFF;
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("SB")
    protected void sb(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        store8(x[rs1] + imm, (byte) x[rs2]);
    }

    @Instruction("SH")
    protected void sh(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        store16(x[rs1] + imm, (short) x[rs2]);
    }

    @Instruction("SW")
    protected void sw(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        store32(x[rs1] + imm, (int) x[rs2]);
    }

    @Instruction("ADDI")
    protected void addi(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] + imm;
        }
    }

    @Instruction("SLTI")
    protected void slti(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] < imm ? 1 : 0;
        }
    }

    @Instruction("SLTIU")
    protected void sltiu(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = Long.compareUnsigned(x[rs1], imm) < 0 ? 1 : 0;
        }
    }

    @Instruction("XORI")
    protected void xori(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] ^ imm;
        }
    }

    @Instruction("ORI")
    protected void ori(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] | imm;
        }
    }

    @Instruction("ANDI")
    protected void andi(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] & imm;
        }
    }

    @Instruction("SLLI")
    protected void slli(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] << shamt;
        }
    }

    @Instruction("SRLI")
    protected void srli(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] >>> shamt;
        }
    }

    @Instruction("SRAI")
    protected void srai(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] >> shamt;
        }
    }

    @Instruction("ADD")
    protected void add(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] + x[rs2];
        }
    }

    @Instruction("SUB")
    protected void sub(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] - x[rs2];
        }
    }

    @Instruction("SLL")
    protected void sll(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] << x[rs2];
        }
    }

    @Instruction("SLT")
    protected void slt(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] < x[rs2] ? 1 : 0;
        }
    }

    @Instruction("SLTU")
    protected void sltu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = Long.compareUnsigned(x[rs1], x[rs2]) < 0 ? 1 : 0;
        }
    }

    @Instruction("XOR")
    protected void xor(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] ^ x[rs2];
        }
    }

    @Instruction("SRL")
    protected void srl(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] >>> x[rs2];
        }
    }

    @Instruction("SRA")
    protected void sra(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] >> x[rs2];
        }
    }

    @Instruction("OR")
    protected void or(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] | x[rs2];
        }
    }

    @Instruction("AND")
    protected void and(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] & x[rs2];
        }
    }

    @Instruction("FENCE")
    protected void fence() {
        // no-op
    }

    @Instruction("ECALL")
    protected void ecall(@ProgramCounter final long pc) {
        this.pc = pc; // raiseException reads the field to store it in mepc/sepc.
        raiseException(R5.EXCEPTION_USER_ECALL + priv);
    }

    @Instruction("EBREAK")
    protected void ebreak(@ProgramCounter final long pc) {
        this.pc = pc; // raiseException reads the field to store it in mepc/sepc.
        raiseException(R5.EXCEPTION_BREAKPOINT);
    }

    ///////////////////////////////////////////////////////////////////
    // RV64I Base Instruction Set

    @Instruction("AUIPCW")
    protected void auipcw(@Field("rd") final int rd,
                        @Field("imm") final int imm,
                        @ProgramCounter final long pc) {
        if (rd != 0) {
            x[rd] = (int) (pc + imm);
        }
    }

    @Instruction("JALW")
    protected void jalw(@Field("rd") final int rd,
                      @Field("imm") final int imm,
                      @ProgramCounter final long pc,
                      @InstructionSize final int instructionSize) {
        if (rd != 0) {
            x[rd] = (int) (pc + instructionSize);
        }

        this.pc = pc + imm;
    }

    @Instruction("JALRW")
    protected void jalrw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("imm") final int imm,
                       @ProgramCounter final long pc,
                       @InstructionSize final int instructionSize) {
        // Compute first in case rs1 == rd and clear lowest bit as per spec.
        final long address = (x[rs1] + imm) & ~1;
        if (rd != 0) {
            x[rd] = (int) (pc + instructionSize);
        }

        this.pc = address;
    }

    @Instruction("ADDIW")
    protected void addiw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] + imm;
        }
    }

    @Instruction("SLLIW")
    protected void slliw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = (int) (x[rs1] << shamt);
        }
    }

    @Instruction("SRLIW")
    protected void srliw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] >>> shamt;
        }
    }

    @Instruction("SRAIW")
    protected void sraiw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] >> shamt;
        }
    }

    @Instruction("ADDW")
    protected void addw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] + (int) x[rs2];
        }
    }

    @Instruction("SUBW")
    protected void subw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] - (int) x[rs2];
        }
    }

    @Instruction("SLLW")
    protected void sllw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] << (int) x[rs2];
        }
    }

    @Instruction("SRLW")
    protected void srlw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] >>> (int) x[rs2];
        }
    }

    @Instruction("SRAW")
    protected void sraw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] >> (int) x[rs2];
        }
    }

    @Instruction("LWU")
    protected void lwu(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws R5MemoryAccessException {
        final long address = x[rs1] + imm;
        final long result = load32(address) & 0xFFFFFFFFL;
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("LD")
    protected void ld(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        final long address = x[rs1] + imm;
        final long result = load64(address);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("SD")
    protected void sd(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws R5MemoryAccessException {
        store64(x[rs1] + imm, x[rs2]);
    }

    ///////////////////////////////////////////////////////////////////
    // RV32/RV64 Zifencei Standard Extension

    @Instruction("FENCE.I")
    protected void fence_i() {
        // no-op
    }

    ///////////////////////////////////////////////////////////////////
    // RV32/RV64 Zicsr Standard Extension

    @Instruction("CSRRW")
    protected boolean csrrw(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrwx(rd, x[rs1], csr);
    }

    @Instruction("CSRRS")
    protected boolean csrrs(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrscx(rd, rs1, csr, x[rs1], true);
    }

    @Instruction("CSRRC")
    protected boolean csrrc(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrscx(rd, rs1, csr, x[rs1], false);
    }

    @Instruction("CSRRWI")
    protected boolean csrrwi(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrwx(rd, rs1, csr);
    }

    @Instruction("CSRRSI")
    protected boolean csrrsi(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrscx(rd, rs1, csr, rs1, true);
    }

    @Instruction("CSRRCI")
    protected boolean csrrci(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrscx(rd, rs1, csr, rs1, false);
    }

    ///////////////////////////////////////////////////////////////////
    // RV32M Standard Extension

    @Instruction("MUL")
    protected void mul(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] * x[rs2];
        }
    }

    @Instruction("MULH")
    protected void mulh(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = Math.multiplyHigh(x[rs1], x[rs2]);
        }
    }

    @Instruction("MULHSU")
    protected void mulhsu(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = signedUnsignedMultiplyHigh(x[rs1], x[rs2]);
        }
    }

    @Instruction("MULHU")
    protected void mulhu(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = unsignedMultiplyHigh(x[rs1], x[rs2]);
        }
    }

    private static long unsignedMultiplyHigh(final long a, final long b) {
        return Math.multiplyHigh(a, b) + (b & (a >> 63)) + (a & (b >> 63));
    }

    private static long signedUnsignedMultiplyHigh(final long signed, final long unsigned) {
        return Math.multiplyHigh(signed, unsigned) + (signed & (unsigned >> 63));
    }

    @Instruction("DIV")
    protected void div(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = -1L;
            } else if (x[rs1] == Long.MIN_VALUE && x[rs2] == -1L) {
                x[rd] = x[rs1];
            } else {
                x[rd] = x[rs1] / x[rs2];
            }
        }
    }

    @Instruction("DIVU")
    protected void divu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = -1L;
            } else {
                x[rd] = Long.divideUnsigned(x[rs1], x[rs2]);
            }
        }
    }

    @Instruction("REM")
    protected void rem(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = x[rs1];
            } else if (x[rs1] == Long.MIN_VALUE && x[rs2] == -1L) {
                x[rd] = 0;
            } else {
                x[rd] = x[rs1] % x[rs2];
            }
        }
    }

    @Instruction("REMU")
    protected void remu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = x[rs1];
            } else {
                x[rd] = Long.remainderUnsigned(x[rs1], x[rs2]);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////
    // RV64M Standard Extension

    @Instruction("MULW")
    protected void mulw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) x[rs1] * (int) x[rs2];
        }
    }

    @Instruction("MULHW")
    protected void mulhw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) ((x[rs1] * x[rs2]) >>> 32);
        }
    }

    @Instruction("MULHSUW")
    protected void mulhsuw(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) ((x[rs1] * (x[rs2] & 0xFFFFFFFFL)) >>> 32);
        }
    }

    @Instruction("MULHUW")
    protected void mulhuw(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) (((x[rs1] & 0xFFFFFFFFL) * (x[rs2] & 0xFFFFFFFFL)) >>> 32);
        }
    }

    @Instruction("DIVW")
    protected void divw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if ((int) x[rs2] == 0) {
                x[rd] = -1;
            } else if ((int) x[rs1] == Integer.MIN_VALUE && (int) x[rs2] == -1) {
                x[rd] = (int) x[rs1];
            } else {
                x[rd] = (int) x[rs1] / (int) x[rs2];
            }
        }
    }

    @Instruction("DIVUW")
    protected void divuw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) {
        if (rd != 0) {
            if ((int) x[rs2] == 0) {
                x[rd] = -1;
            } else {
                x[rd] = Integer.divideUnsigned((int) x[rs1], (int) x[rs2]);
            }
        }
    }

    @Instruction("REMW")
    protected void remw(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if ((int) x[rs2] == 0) {
                x[rd] = (int) x[rs1];
            } else if ((int) x[rs1] == Integer.MIN_VALUE && (int) x[rs2] == -1) {
                x[rd] = 0;
            } else {
                x[rd] = (int) x[rs1] % (int) x[rs2];
            }
        }
    }

    @Instruction("REMUW")
    protected void remuw(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) {
        if (rd != 0) {
            if ((int) x[rs2] == 0) {
                x[rd] = (int) x[rs1];
            } else {
                x[rd] = Integer.remainderUnsigned((int) x[rs1], (int) x[rs2]);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////
    // RV32A Standard Extension

    @Instruction("LR.W")
    protected void lr_w(@Field("rd") final int rd,
                      @Field("rs1") final int rs1) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int result = load32(address);
        reservation_set = address;

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("SC.W")
    protected void sc_w(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final int result;
        final long address = x[rs1];
        if (address == reservation_set) {
            store32(address, (int) x[rs2]);
            result = 0;
        } else {
            result = 1;
        }

        reservation_set = -1; // Always invalidate as per spec.

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("AMOSWAP.W")
    protected void amoswap_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOADD.W")
    protected void amoadd_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, a + b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOXOR.W")
    protected void amoxor_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, a ^ b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOAND.W")
    protected void amoand_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, a & b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOOR.W")
    protected void amoor_w(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, a | b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMIN.W")
    protected void amomin_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, Math.min(a, b));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMAX.W")
    protected void amomax_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, Math.max(a, b));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMINU.W")
    protected void amominu_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];


        store32(address, Integer.compareUnsigned(a, b) < 0 ? a : b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMAXU.W")
    protected void amomaxu_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final int a = load32(address);
        final int b = (int) x[rs2];

        store32(address, Integer.compareUnsigned(a, b) > 0 ? a : b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    ///////////////////////////////////////////////////////////////////
    // RV64A Standard Extension

    @Instruction("LR.D")
    protected void lr_d(@Field("rd") final int rd,
                      @Field("rs1") final int rs1) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long result = load64(address);
        reservation_set = address;

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("SC.D")
    protected void sc_d(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final int result;
        final long address = x[rs1];
        if (address == reservation_set) {
            store64(address, x[rs2]);
            result = 0;
        } else {
            result = 1;
        }

        reservation_set = -1; // Always invalidate as per spec.

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @Instruction("AMOSWAP.D")
    protected void amoswap_d(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOADD.D")
    protected void amoadd_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, a + b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOXOR.D")
    protected void amoxor_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, a ^ b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOAND.D")
    protected void amoand_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, a & b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOOR.D")
    protected void amoor_d(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, a | b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMIN.D")
    protected void amomin_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, Math.min(a, b));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMAX.D")
    protected void amomax_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, Math.max(a, b));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMINU.D")
    protected void amominu_d(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, Long.compareUnsigned(a, b) < 0 ? a : b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @Instruction("AMOMAXU.D")
    protected void amomaxu_d(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws R5MemoryAccessException {
        final long address = x[rs1];
        final long a = load64(address);
        final long b = x[rs2];

        store64(address, Long.compareUnsigned(a, b) > 0 ? a : b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Privileged Instructions

    @Instruction("SRET")
    protected boolean sret() throws R5IllegalInstructionException {
        if (priv < R5.PRIVILEGE_S) {
            throw new R5IllegalInstructionException();
        }

        if ((mstatus & R5.STATUS_TSR_MASK) != 0 && priv < R5.PRIVILEGE_M) {
            throw new R5IllegalInstructionException();
        }

        final int spp = (int) ((mstatus & R5.STATUS_SPP_MASK) >>> R5.STATUS_SPP_SHIFT); // Previous privilege level.
        final int spie = (int) ((mstatus & R5.STATUS_SPIE_MASK) >>> R5.STATUS_SPIE_SHIFT); // Previous interrupt-enable state.
        mstatus = (mstatus & ~R5.STATUS_SIE_MASK) | ((long) spie << R5.STATUS_SIE_SHIFT);
        mstatus |= R5.STATUS_SPIE_MASK;
        mstatus &= ~R5.STATUS_SPP_MASK;
        mstatus &= ~R5.STATUS_MPRV_MASK;

        setPrivilege(spp);

        pc = sepc;
        return true; // Exit trace; virtual memory access may have changed.
    }

    @Instruction("MRET")
    protected boolean mret() throws R5IllegalInstructionException {
        if (priv < R5.PRIVILEGE_M) {
            throw new R5IllegalInstructionException();
        }

        final int mpp = (int) ((mstatus & R5.STATUS_MPP_MASK) >>> R5.STATUS_MPP_SHIFT); // Previous privilege level.
        final int mpie = (int) ((mstatus & R5.STATUS_MPIE_MASK) >>> R5.STATUS_MPIE_SHIFT); // Previous interrupt-enable state.
        mstatus = (mstatus & ~R5.STATUS_MIE_MASK) | ((long) mpie << R5.STATUS_MIE_SHIFT);
        mstatus |= R5.STATUS_MPIE_MASK;
        mstatus &= ~R5.STATUS_MPP_MASK;
        if (mpp != R5.PRIVILEGE_M) {
            mstatus &= ~R5.STATUS_MPRV_MASK;
        }

        setPrivilege(mpp);

        pc = mepc;
        return true; // Exit trace; virtual memory access may have changed.
    }

    @Instruction("WFI")
    protected boolean wfi() throws R5IllegalInstructionException {
        if (priv == R5.PRIVILEGE_U) {
            throw new R5IllegalInstructionException();
        }
        if ((mstatus & R5.STATUS_TW_MASK) != 0 && priv == R5.PRIVILEGE_S) {
            throw new R5IllegalInstructionException();
        }

        if ((mip.get() & mie) != 0) {
            return false;
        }

        waitingForInterrupt = true;
        return true; // Exit trace.
    }

    @Instruction("SFENCE.VMA")
    protected boolean sfence_vma(@Field("rs1") final int rs1,
                               @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        if (priv == R5.PRIVILEGE_U) {
            throw new R5IllegalInstructionException();
        }
        if ((mstatus & R5.STATUS_TVM_MASK) != 0 && priv == R5.PRIVILEGE_S) {
            throw new R5IllegalInstructionException();
        }

        if (rs1 == 0) {
            flushTLB();
        } else {
            flushTLB(x[rs1]);
        }

        return true; // Exit trace, need to re-fetch.
    }

    ///////////////////////////////////////////////////////////////////
    // RV32F Standard Extension

    private static int checkFloat(final long value) {
        // As per V1p74: operations except for FLW, FSW, FMV check if operands are correctly
        //               NaN-boxed, if not the operand is replaced with the canonical NaN.
        if ((value & R5.NAN_BOXING_MASK) != R5.NAN_BOXING_MASK) {
            return SoftFloat.nan();
        } else {
            return (int) value;
        }
    }

    @Instruction("FLW")
    protected void flw(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws R5IllegalInstructionException, R5MemoryAccessException {
        checkFPUEnabled();
        f[rd] = load32(x[rs1] + imm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSW")
    protected void fsw(@Field("rs1") final int rs1,
                     @Field("rs2") final int rs2,
                     @Field("imm") final int imm) throws R5IllegalInstructionException, R5MemoryAccessException {
        checkFPUEnabled();
        store32(x[rs1] + imm, (int) f[rs2]);
    }

    @Instruction("FMADD.S")
    protected void fmadd_s(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("rs3") final int rs3,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.muladd(checkFloat(f[rs1]), checkFloat(f[rs2]), checkFloat(f[rs3]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMSUB.S")
    protected void fmsub_s(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("rs3") final int rs3,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.mulsub(checkFloat(f[rs1]), checkFloat(f[rs2]), checkFloat(f[rs3]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FNMSUB.S")
    protected void fnmsub_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2,
                          @Field("rs3") final int rs3,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.muladd(fpu32.neg(checkFloat(f[rs1])), checkFloat(f[rs2]), checkFloat(f[rs3]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FNMADD.S")
    protected void fnmadd_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2,
                          @Field("rs3") final int rs3,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.mulsub(fpu32.neg(checkFloat(f[rs1])), checkFloat(f[rs2]), checkFloat(f[rs3]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FADD.S")
    protected void fadd_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.add(checkFloat(f[rs1]), checkFloat(f[rs2]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSUB.S")
    protected void fsub_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.sub(checkFloat(f[rs1]), checkFloat(f[rs2]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMUL.S")
    protected void fmul_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.mul(checkFloat(f[rs1]), checkFloat(f[rs2]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FDIV.S")
    protected void fdiv_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.div(checkFloat(f[rs1]), checkFloat(f[rs2]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSQRT.S")
    protected void fsqrt_s(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.sqrt(checkFloat(f[rs1]), rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJ.S")
    protected void fsgnj_s(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final long value = checkFloat(f[rs1]) & ~SoftFloat.SIGN_MASK;
        f[rd] = value | (checkFloat(f[rs2]) & SoftFloat.SIGN_MASK) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJN.S")
    protected void fsgnjn_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final long value = checkFloat(f[rs1]) & ~SoftFloat.SIGN_MASK;
        f[rd] = value | (~checkFloat(f[rs2]) & SoftFloat.SIGN_MASK) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJX.S")
    protected void fsgnjx_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = f[rs1] ^ (checkFloat(f[rs2]) & SoftFloat.SIGN_MASK) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMIN.S")
    protected void fmin_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = fpu32.min(checkFloat(f[rs1]), checkFloat(f[rs2])) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMAX.S")
    protected void fmax_s(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = fpu32.max(checkFloat(f[rs1]), checkFloat(f[rs2])) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.W.S")
    protected void fcvt_w_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final int value = fpu32.floatToInt(checkFloat(f[rs1]), rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.WU.S")
    protected void fcvt_wu_s(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final int value = fpu32.floatToUnsignedInt(checkFloat(f[rs1]), rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FMV.X.W")
    protected void fmv_x_w(@Field("rd") final int rd,
                         @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        if (rd != 0) {
            x[rd] = (int) f[rs1];
        }
    }

    @Instruction("FEQ.S")
    protected void feq_s(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean areEqual = fpu32.equals(checkFloat(f[rs1]), checkFloat(f[rs2]));
        if (rd != 0) {
            x[rd] = areEqual ? 1 : 0;
        }
    }

    @Instruction("FLT.S")
    protected void flt_s(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean isLessThan = fpu32.lessThan(checkFloat(f[rs1]), checkFloat(f[rs2]));
        if (rd != 0) {
            x[rd] = isLessThan ? 1 : 0;
        }
    }

    @Instruction("FLE.S")
    protected void fle_s(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean isLessOrEqual = fpu32.lessOrEqual(checkFloat(f[rs1]), checkFloat(f[rs2]));
        if (rd != 0) {
            x[rd] = isLessOrEqual ? 1 : 0;
        }
    }

    @Instruction("FCLASS.S")
    protected void fclass_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        if (rd != 0) {
            x[rd] = fpu32.classify(checkFloat(f[rs1]));
        }
    }

    @Instruction("FCVT.S.W")
    protected void fcvt_s_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.intToFloat((int) x[rs1], rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.S.WU")
    protected void fcvt_s_wu(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.unsignedIntToFloat((int) x[rs1], rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMV.W.X")
    protected void fmv_w_x(@Field("rd") final int rd,
                         @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = x[rs1] | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    ///////////////////////////////////////////////////////////////////
    // RV64F Standard Extension

    @Instruction("FCVT.L.S")
    protected void fcvt_l_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final long value = fpu32.floatToLong((int) f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.LU.S")
    protected void fcvt_lu_s(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final long value = fpu32.floatToUnsignedLong((int) f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.S.L")
    protected void fcvt_s_l(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.longToFloat(x[rs1], rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.S.LU")
    protected void fcvt_s_lu(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu32.unsignedLongToFloat(x[rs1], rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    ///////////////////////////////////////////////////////////////////
    // RV32D Standard Extension

    @Instruction("FLD")
    protected void fld(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws R5IllegalInstructionException, R5MemoryAccessException {
        checkFPUEnabled();
        f[rd] = load64(x[rs1] + imm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSD")
    protected void fsd(@Field("rs1") final int rs1,
                     @Field("rs2") final int rs2,
                     @Field("imm") final int imm) throws R5IllegalInstructionException, R5MemoryAccessException {
        checkFPUEnabled();
        store64(x[rs1] + imm, f[rs2]);
    }

    @Instruction("FMADD.D")
    protected void fmadd_d(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("rs3") final int rs3,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.muladd(f[rs1], f[rs2], f[rs3], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMSUB.D")
    protected void FMSUB_D(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("rs3") final int rs3,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.mulsub(f[rs1], f[rs2], f[rs3], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FNMSUB.D")
    protected void fnmsub_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2,
                          @Field("rs3") final int rs3,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.muladd(fpu64.neg(f[rs1]), f[rs2], f[rs3], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FNMADD.D")
    protected void fnmadd_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2,
                          @Field("rs3") final int rs3,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.mulsub(fpu64.neg(f[rs1]), f[rs2], f[rs3], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FADD.D")
    protected void fadd_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.add(f[rs1], f[rs2], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSUB.D")
    protected void fsub_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.sub(f[rs1], f[rs2], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMUL.D")
    protected void fmul_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.mul(f[rs1], f[rs2], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FDIV.D")
    protected void fdiv_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.div(f[rs1], f[rs2], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSQRT.D")
    protected void fsqrt_d(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.sqrt(f[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJ.D")
    protected void fsgnj_d(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final long value = f[rs1] & ~SoftDouble.SIGN_MASK;
        f[rd] = value | (f[rs2] & SoftDouble.SIGN_MASK);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJN.D")
    protected void fsgnjn_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final long value = f[rs1] & ~SoftDouble.SIGN_MASK;
        f[rd] = value | (~f[rs2] & SoftDouble.SIGN_MASK);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FSGNJX.D")
    protected void fsgnjx_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = f[rs1] ^ (f[rs2] & SoftDouble.SIGN_MASK);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMIN.D")
    protected void fmin_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = fpu64.min(f[rs1], f[rs2]);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMAX.D")
    protected void fmax_d(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = fpu64.max(f[rs1], f[rs2]);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.S.D")
    protected void fcvt_s_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.doubleToFloat(f[rs1], rm) | R5.NAN_BOXING_MASK;
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.D.S")
    protected void fcvt_d_s(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.floatToDouble((int) f[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FEQ.D")
    protected void feq_d(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean areEqual = fpu64.equals(f[rs1], f[rs2]);
        if (rd != 0) {
            x[rd] = areEqual ? 1 : 0;
        }
    }

    @Instruction("FLT.D")
    protected void flt_d(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean isLessThan = fpu64.lessThan(f[rs1], f[rs2]);
        if (rd != 0) {
            x[rd] = isLessThan ? 1 : 0;
        }
    }

    @Instruction("FLE.D")
    protected void fle_d(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) throws R5IllegalInstructionException {
        checkFPUEnabled();
        final boolean isLessOrEqual = fpu64.lessOrEqual(f[rs1], f[rs2]);
        if (rd != 0) {
            x[rd] = isLessOrEqual ? 1 : 0;
        }
    }

    @Instruction("FCLASS.D")
    protected void fclass_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        if (rd != 0) {
            x[rd] = fpu64.classify(f[rs1]);
        }
    }

    @Instruction("FCVT.W.D")
    protected void fcvt_w_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final int value = fpu64.doubleToInt(f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.WU.D")
    protected void fcvt_wu_d(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final int value = fpu64.doubleToUnsignedInt(f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.D.W")
    protected void fcvt_d_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.intToDouble((int) x[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.D.WU")
    protected void fcvt_d_wu(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.unsignedIntToDouble((int) x[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    ///////////////////////////////////////////////////////////////////
    // RV64D Standard Extension

    @Instruction("FCVT.L.D")
    protected void fcvt_l_d(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final long value = fpu64.doubleToLong(f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FCVT.LU.D")
    protected void fcvt_lu_d(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        final long value = fpu64.doubleToUnsignedLong(f[rs1], rm);
        if (rd != 0) {
            x[rd] = value;
        }
    }

    @Instruction("FMV.X.D")
    protected void fmv_x_d(@Field("rd") final int rd,
                         @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        if (rd != 0) {
            x[rd] = f[rs1];
        }
    }

    @Instruction("FCVT.D.L")
    protected void fcvt_d_l(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.longToDouble(x[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FCVT.D.LU")
    protected void fcvt_d_lu(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rm") int rm) throws R5IllegalInstructionException {
        checkFPUEnabled();
        rm = resolveRoundingMode(rm);
        f[rd] = fpu64.unsignedLongToDouble(x[rs1], rm);
        fs = R5.FS_DIRTY;
    }

    @Instruction("FMV.D.X")
    protected void fmv_d_x(@Field("rd") final int rd,
                         @Field("rs1") final int rs1) throws R5IllegalInstructionException {
        checkFPUEnabled();
        f[rd] = x[rs1];
        fs = R5.FS_DIRTY;
    }

    ///////////////////////////////////////////////////////////////////

    private enum MemoryAccessType {
        LOAD(R5.PTE_R_MASK),
        STORE(R5.PTE_W_MASK),
        FETCH(R5.PTE_X_MASK),
        ;

        public final int mask;

        MemoryAccessType(final int mask) {
            this.mask = mask;
        }
    }

    private static final class MMIOTLBEntry {
        public long hash = -1;
        public long toOffset;
        public MemoryMappedDevice device;
        public long windowStart;
        public long windowEnd;

        public boolean matches(final long address, final long tag, final int sizeLog2) {
            return hash == ((address & ~R5.PAGE_ADDRESS_MASK) | tag)
                && Long.compareUnsigned(address, windowStart) >= 0
                && Long.compareUnsigned(address + (1 << sizeLog2) - 1, windowEnd) <= 0;
        }
    }

    private record PageAccess(MemoryMappedDevice device, long toOffset) {}

    final class DebugInterface implements CPUDebugInterface {
        private final Collection<LongConsumer> breakpointListeners = new ArrayList<>();
        private final LongSortedSet breakpoints = new LongAVLTreeSet();

        @Override
        public long getProgramCounter() {
            return pc;
        }

        @Override
        public void setProgramCounter(final long value) {
            pc = value;
        }

        @Override
        public void step() {
            interpret(true, true);
        }

        @Override
        public long[] getGeneralRegisters() {
            return x;
        }

        @Override
        public byte[] loadDebug(final long address, final int size) throws R5MemoryAccessException {
            final byte[] mem = new byte[size];
            if (size == 0) return mem;
            PageAccess entry = getPageDebug(address, MemoryAccessType.LOAD);
            int i = 0;
            while (true) {
                try {
                    mem[i] = (byte) entry.device().load((int) (address + i + entry.toOffset()), 0);
                } catch (final MemoryAccessException e) {
                    // Partial reads are okay
                    return Arrays.copyOf(mem, i);
                }
                i++;
                if (i == size) break;
                if (((address + i) & R5.PAGE_ADDRESS_MASK) == 0) {
                    entry = getPageDebug(address + i, MemoryAccessType.LOAD);
                }
            }
            return mem;
        }

        @Override
        public int storeDebug(final long address, final byte[] data) throws R5MemoryAccessException {
            PageAccess entry = getPageDebug(address, MemoryAccessType.STORE);
            int i = 0;
            while (true) {
                try {
                    entry.device().store((int) (address + i + entry.toOffset()), data[i], 0);
                } catch (final MemoryAccessException e) {
                    return i;
                }
                i++;
                if (i == data.length) break;
                if (((address + i) & R5.PAGE_ADDRESS_MASK) == 0) {
                    entry = getPageDebug(address + i, MemoryAccessType.STORE);
                }
            }
            return i;
        }

        @Override
        public void addBreakpointListener(final LongConsumer listener) {
            if (!breakpointListeners.contains(listener)) {
                breakpointListeners.add(listener);
            }
        }

        @Override
        public void removeBreakpointListener(final LongConsumer listener) {
            breakpointListeners.remove(listener);
        }

        @Override
        public void addBreakpoint(final long address) {
            breakpoints.add(address);

            final int index = tlbIndex(address);
            if (fetchTLBHash[index] == ((address & ~R5.PAGE_ADDRESS_MASK) | fetchTLBTag)) {
                if (fetchTLBBreakpoints[index] == null) {
                    fetchTLBBreakpoints[index] = new LongOpenHashSet();
                }
                fetchTLBBreakpoints[index].add(address);
            }
        }

        @Override
        public void removeBreakpoint(final long address) {
            breakpoints.remove(address);

            final int index = tlbIndex(address);
            if (fetchTLBHash[index] == ((address & ~R5.PAGE_ADDRESS_MASK) | fetchTLBTag)
                && fetchTLBBreakpoints[index] != null) {
                fetchTLBBreakpoints[index].remove(address);
            }
        }

        /**
         * Used by the GDB stub for debugging. We have special requirements compared to normal memory access.
         * 1. Need to bypass access protection, particularly the R/W bits
         * 2. Would like to avoid modifying CPU state as much as possible, including TLB entries.
         */
        private PageAccess getPageDebug(final long address, final MemoryAccessType accessType) throws R5MemoryAccessException {
            final PageAccess cached = tryGetPageAccess(address, accessType);
            if (cached != null) {
                return cached;
            } else {
                final long physicalAddress = getPhysicalAddress(address, accessType, true);
                final MappedMemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
                if (range == null) {
                    throw getPageFaultException(accessType, address);
                }

                // Resolved directly, to avoid modifying the TLB.
                return new PageAccess(range.device, physicalAddress - address - range.start);
            }
        }

        @Nullable
        private PageAccess tryGetPageAccess(final long address, final MemoryAccessType accessType) {
            final int index = tlbIndex(address);
            final long hash = (address & ~R5.PAGE_ADDRESS_MASK) | tagFor(accessType);
            return switch (accessType) {
                case LOAD -> loadTLBHash[index] == hash
                    ? new PageAccess(loadTLBDevice[index], loadTLBToOffset[index]) : null;
                case STORE -> storeTLBHash[index] == hash
                    ? new PageAccess(storeTLBDevice[index], storeTLBToOffset[index]) : null;
                case FETCH -> fetchTLBHash[index] == hash
                    ? new PageAccess(fetchTLBDevice[index], fetchTLBToOffset[index]) : null;
            };
        }

        private long tagFor(final MemoryAccessType accessType) {
            return accessType == MemoryAccessType.FETCH ? fetchTLBTag : dataTLBTag;
        }

        void handleBreakpoint(final long pc) {
            for (final LongConsumer listener : breakpointListeners) {
                listener.accept(pc);
            }
        }
    }
}
