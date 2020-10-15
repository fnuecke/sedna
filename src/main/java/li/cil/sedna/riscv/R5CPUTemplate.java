package li.cil.sedna.riscv;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;
import li.cil.sedna.instruction.InstructionDefinition.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RISC-V
 * <p>
 * Based on ISA specifications found at https://riscv.org/technical/specifications/
 * <ul>
 * <li>Volume 1, Unprivileged Spec v.20191213</li>
 * <li>Volume 2, Privileged Spec v.20190608</li>
 * </ul>
 * <p>
 * Implemented extensions:
 * <ul>
 * <li>RV32I Base Integer Instruction Set, Version 2.1</li>
 * <li>"Zifencei" Instruction-Fetch Fence, Version 2.0</li>
 * <li>"M" Standard Extension for Integer Multiplication and Division, Version 2.0</li>
 * <li>"A" Standard Extension for Atomic Instructions, Version 2.1</li>
 * <li>"Zicsr", Control and Status Register (CSR) Instructions, Version 2.0</li>
 * <li>TODO "F" Standard Extension for Single-Precision Floating-Point, Version 2.2</li>
 * <li>TODO "D"</li>
 * <li>"C" Standard Extension for Compressed Instructions, Version 2.0</li>
 * </ul>
 */
@Serialized
final class R5CPUTemplate implements R5CPU {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int PC_INIT = 0x1000; // Initial position of program counter.

    private static final int XLEN = 32; // Integer register width.

    // Base ISA descriptor CSR (misa) (V2p16).
    private static final int MISA = (R5.mxl(XLEN) << (XLEN - 2)) | R5.isa('I', 'M', 'A', 'S', 'U', 'C'); // 'F', 'D'

    // UBE, SBE, MBE hardcoded to zero for little endianness.
    private static final int MSTATUS_MASK = ~R5.STATUS_UBE_MASK;

    // No time and no high perf counters.
    private static final int COUNTEREN_MASK = R5.MCOUNTERN_CY | R5.MCOUNTERN_IR;

    // Supervisor status (sstatus) CSR mask over mstatus.
    private static final int SSTATUS_MASK = (R5.STATUS_UIE_MASK | R5.STATUS_SIE_MASK |
                                             R5.STATUS_UPIE_MASK | R5.STATUS_SPIE_MASK |
                                             R5.STATUS_SPP_MASK | R5.STATUS_FS_MASK |
                                             R5.STATUS_XS_MASK | R5.STATUS_SUM_MASK |
                                             R5.STATUS_MXR_MASK | R5.STATUS_SD_MASK);

    // Translation look-aside buffer config.
    private static final int TLB_SIZE = 256; // Must be a power of two for fast modulo via `& (TLB_SIZE - 1)`.

    ///////////////////////////////////////////////////////////////////
    // RV32I
    private int pc; // Program counter.
    private final int[] x = new int[32]; // Integer registers.

    ///////////////////////////////////////////////////////////////////
    // RV32F
//    private final float[] f = new float[32]; // Float registers.
//    private byte fflags; // fcsr[4:0] := NV . DZ . OF . UF . NX
//    private byte frm; // fcsr[7:5]

    ///////////////////////////////////////////////////////////////////
    // RV32A
    private int reservation_set = -1; // Reservation set for RV32A's LR/SC.

    ///////////////////////////////////////////////////////////////////
    // User-level CSRs
    private long mcycle;

    // Machine-level CSRs
    private int mstatus, mstatush; // Machine Status Register
    private int mtvec; // Machine Trap-Vector Base-Address Register; 0b11=Mode: 0=direct, 1=vectored
    private int medeleg, mideleg; // Machine Trap Delegation Registers
    private final AtomicInteger mip = new AtomicInteger(); // Pending Interrupts
    private int mie; // Enabled Interrupts
    private int mcounteren; // Machine Counter-Enable Register
    private int mscratch; // Machine Scratch Register
    private int mepc; // Machine Exception Program Counter
    private int mcause; // Machine Cause Register
    private int mtval; //  Machine Trap Value Register

    // Supervisor-level CSRs
    private int stvec; // Supervisor Trap Vector Base Address Register; 0b11=Mode: 0=direct, 1=vectored
    private int scounteren; // Supervisor Counter-Enable Register
    private int sscratch; // Supervisor Scratch Register
    private int sepc; // Supervisor Exception Program Counter
    private int scause; // Supervisor Cause Register
    private int stval; // Supervisor Trap Value Register
    private int satp; // Supervisor Address Translation and Protection Register

    ///////////////////////////////////////////////////////////////////
    // Misc. state
    private int priv; // Current privilege level.
    private boolean waitingForInterrupt;

    ///////////////////////////////////////////////////////////////////
    // Memory access

    // Translation look-aside buffers.
    private final transient R5CPUTLBEntry[] fetchTLB = new R5CPUTLBEntry[TLB_SIZE];
    private final transient R5CPUTLBEntry[] loadTLB = new R5CPUTLBEntry[TLB_SIZE];
    private final transient R5CPUTLBEntry[] storeTLB = new R5CPUTLBEntry[TLB_SIZE];

    // Access to physical memory for load/store operations.
    private final transient MemoryMap physicalMemory;

    ///////////////////////////////////////////////////////////////////
    // Real time counter -- at least in RISC-V Linux 5.1 the mtime CSR is needed in add_device_randomness
    // where it doesn't use the SBI. Not implementing it would cause an illegal instruction exception
    // halting the system.
    private final transient RealTimeCounter rtc;

    @SuppressWarnings("RedundantCast")
    public R5CPUTemplate(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        // This cast is necessary so that stack frame computation in ASM does not throw
        // an exception from trying to load the realization class we're generating while
        // we're generating it.
        this.rtc = rtc != null ? rtc : (RealTimeCounter) (Object) this;
        this.physicalMemory = physicalMemory;

        for (int i = 0; i < TLB_SIZE; i++) {
            fetchTLB[i] = new R5CPUTLBEntry();
        }
        for (int i = 0; i < TLB_SIZE; i++) {
            loadTLB[i] = new R5CPUTLBEntry();
        }
        for (int i = 0; i < TLB_SIZE; i++) {
            storeTLB[i] = new R5CPUTLBEntry();
        }

        reset();
    }

    @Override
    public void reset() {
        reset(true, PC_INIT);
    }

    @Override
    public void reset(final boolean hard, final int pc) {
        this.pc = pc;
        waitingForInterrupt = false;

        // Volume 2, 3.3 Reset
        priv = R5.PRIVILEGE_M;
        mstatus = mstatus & ~R5.STATUS_MIE_MASK;
        mstatus = mstatus & ~R5.STATUS_MPRV_MASK;
        mcause = 0;

        flushTLB();

        if (hard) {
            Arrays.fill(x, 0);

            reservation_set = -1;

            mcycle = 0;

            mstatus = 0;
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
    public long getTime() {
        return mcycle;
    }

    @Override
    public int getFrequency() {
        return 50_000_000;
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
        return mip.get();
    }

    public void step(final int cycles) {
        if (waitingForInterrupt) {
            mcycle += cycles;
            return;
        }

        final long cycleLimit = mcycle + cycles;
        while (!waitingForInterrupt && mcycle < cycleLimit) {
            final int pending = mip.get() & mie;
            if (pending != 0) {
                raiseInterrupt(pending);
            }

            interpret();
        }

        if (waitingForInterrupt && mcycle < cycleLimit) {
            mcycle = cycleLimit;
        }
    }

    private void interpret() {
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
            final R5CPUTLBEntry cache = fetchPage(pc);
            final int instOffset = pc + cache.toOffset;
            final int instEnd = instOffset - (pc & R5.PAGE_ADDRESS_MASK) // Page start.
                                + ((1 << R5.PAGE_ADDRESS_SHIFT) - 2); // Page size minus 16bit.

            int inst;
            if (instOffset < instEnd) { // Likely case, instruction fully inside page.
                inst = cache.device.load(instOffset, Sizes.SIZE_32_LOG2);
            } else { // Unlikely case, instruction may leave page if it is 32bit.
                inst = cache.device.load(instOffset, Sizes.SIZE_16_LOG2) & 0xFFFF;
                if ((inst & 0b11) == 0b11) { // 32bit instruction.
                    final R5CPUTLBEntry highCache = fetchPage(pc + 2);
                    inst |= highCache.device.load(pc + 2 + highCache.toOffset, Sizes.SIZE_16_LOG2) << 16;
                }
            }

            interpretTrace(cache, inst, pc, instOffset, instEnd);
        } catch (final MemoryAccessException e) {
            raiseException(R5.convertMemoryException(e.getType()), e.getAddress());
        }
    }

    @SuppressWarnings("LocalCanBeFinal") // `pc` and `instOffset` get updated by the generated code replacing decode().
    private void interpretTrace(final R5CPUTLBEntry cache, int inst, int pc, int instOffset, final int instEnd) {
        try { // Catch any exceptions to patch PC field.
            for (; ; ) { // End of page check at the bottom since we enter with a valid inst.
                mcycle++;

                ///////////////////////////////////////////////////////////////////
                // This is the hook we replace when generating the decoder code. //
                decode();                                                        //
                // See R5CPUGenerator.                                           //
                ///////////////////////////////////////////////////////////////////

                if (instOffset < instEnd) { // Likely case: we're still fully in the page.
                    inst = cache.device.load(instOffset, Sizes.SIZE_32_LOG2);
                } else { // Unlikely case: we reached the end of the page. Leave to do interrupts and cycle check.
                    this.pc = pc;
                    return;
                }
            }
        } catch (final R5IllegalInstructionException e) {
            this.pc = pc;
            raiseException(R5.EXCEPTION_ILLEGAL_INSTRUCTION, inst);
        } catch (final MemoryAccessException e) {
            this.pc = pc;
            raiseException(R5.convertMemoryException(e.getType()), e.getAddress());
        }
    }

    @SuppressWarnings("RedundantThrows")
    private static void decode() throws R5IllegalInstructionException, MemoryAccessException {
        throw new UnsupportedOperationException();
    }

    private boolean csrrw_impl(final int rd, final int a, final int csr) throws R5IllegalInstructionException {
        final boolean exitTrace;

        checkCSR(csr, true);
        if (rd != 0) { // Explicit check, spec says no read side-effects when rd = 0.
            final int b = readCSR(csr);
            exitTrace = writeCSR(csr, a);
            x[rd] = b; // Write to register last, avoid lingering side-effect when write errors.
        } else {
            exitTrace = writeCSR(csr, a);
        }

        return exitTrace;
    }

    private boolean csrrx_impl(final int rd, final int rs1, final int csr, final int a, final boolean isSet) throws R5IllegalInstructionException {
        final boolean exitTrace;

        final boolean mayChange = rs1 != 0;

        final int b;
        if (mayChange) {
            checkCSR(csr, true);
            b = readCSR(csr);
            final int masked = isSet ? (a | b) : (~a & b);
            exitTrace = writeCSR(csr, masked);
        } else {
            checkCSR(csr, false);
            b = readCSR(csr);
            exitTrace = false;
        }

        if (rd != 0) {
            x[rd] = b;
        }

        return exitTrace;
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
    private int readCSR(final int csr) throws R5IllegalInstructionException {
        switch (csr) {
            // Floating-Point Control and Status Registers
//            case 0x001: { // fflags, Floating-Point Accrued Exceptions.
//                return fflags;
//            }
//            case 0x002: { // frm, Floating-Point Dynamic Rounding Mode.
//                return frm;
//            }
//            case 0x003: { // fcsr, Floating-Point Control and Status Register (frm + fflags).
//                return (frm << 5) | fflags;
//            }

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
            case 0x100: { // sstatus, Supervisor status register.
                return mstatus & SSTATUS_MASK;
            }
            // 0x102: sedeleg, Supervisor exception delegation register.
            // 0x103: sideleg, Supervisor interrupt delegation register.
            case 0x104: { // sie, Supervisor interrupt-enable register.
                return mie & mideleg; // Effectively read-only because we don't implement N.
            }
            case 0x105: { // stvec, Supervisor trap handler base address.
                return stvec;
            }
            case 0x106: { // scounteren, Supervisor counter enable.
                return scounteren;
            }

            // Supervisor Trap Handling
            case 0x140: { // sscratch Scratch register for supervisor trap handlers.
                return sscratch;
            }
            case 0x141: { // sepc Supervisor exception program counter.
                return sepc;
            }
            case 0x142: { // scause Supervisor trap cause.
                return scause;
            }
            case 0x143: { // stval Supervisor bad address or instruction.
                return stval;
            }
            case 0x144: { // sip Supervisor interrupt pending.
                return mip.get() & mideleg; // Effectively read-only because we don't implement N.
            }

            // Supervisor Protection and Translation
            case 0x180: { // satp Supervisor address translation and protection.
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
            case 0x300: { // mstatus Machine status register.
                return mstatus;
            }
            case 0x301: { // misa ISA and extensions
                return MISA;
            }
            case 0x302: { // medeleg Machine exception delegation register.
                return medeleg;
            }
            case 0x303: { // mideleg Machine interrupt delegation register.
                return mideleg;
            }
            case 0x304: { // mie Machine interrupt-enable register.
                return mie;
            }
            case 0x305: { // mtvec Machine trap-handler base address.
                return mtvec;
            }
            case 0x306: { // mcounteren Machine counter enable.
                return mcounteren;
            }
            case 0x310: {// mstatush, Additional machine status register, RV32 only.
                return mstatush;
            }

            // Debug/Trace Registers
            case 0x7A0: { // tselect
                return 0;
            }
            case 0x7A1: { // tdata1
                return 0;
            }
            case 0x7A2: { // tdata2
                return 0;
            }
            case 0x7A3: { // tdata3
                return 0;
            }

            // Machine Trap Handling
            case 0x340: { // mscratch Scratch register for machine trap handlers.
                return mscratch;
            }
            case 0x341: { // mepc Machine exception program counter.
                return mepc;
            }
            case 0x342: { // mcause Machine trap cause.
                return mcause;
            }
            case 0x343: { // mtval Machine bad address or instruction.
                return mtval;
            }
            case 0x344: { // mip Machine interrupt pending.
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
            case 0xB00: // mcycle, Machine cycle counter.
            case 0xB02: { // minstret, Machine instructions-retired counter.
                return (int) mcycle;
            }
            //0xB03: mhpmcounter3, Machine performance-monitoring counter.
            //0xB04...0xB1F: mhpmcounter4...mhpmcounter31, Machine performance-monitoring counter.
            case 0xB80: // mcycleh, Upper 32 bits of mcycle, RV32 only.
            case 0xB82: { // minstreth, Upper 32 bits of minstret, RV32 only.
                return (int) (mcycle >> 32);
            }
            //0xB83: mhpmcounter3h, Upper 32 bits of mhpmcounter3, RV32 only.
            //0xB84...0xB9F: mhpmcounter4h...mhpmcounter31h, Upper 32 bits of mhpmcounter4, RV32 only.

            // Counters and Timers
            case 0xC00:  // cycle
            case 0xC02: { // instret
                // See Volume 2 p36: mcounteren/scounteren define availability to next lowest privilege level.
                if (priv < R5.PRIVILEGE_M) {
                    final int counteren;
                    if (priv < R5.PRIVILEGE_S) {
                        counteren = scounteren;
                    } else {
                        counteren = mcounteren;
                    }

                    // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                    if ((counteren & (1 << (csr & 0b11))) == 0) {
                        throw new R5IllegalInstructionException();
                    }
                }
                return (int) mcycle;
            }
            case 0xC01: { // time
                return (int) rtc.getTime();
            }
            // 0xC03 ... 0xC1F: hpmcounter3 ... hpmcounter31
            case 0xC80:  // cycleh
            case 0xC82: { // instreth
                // See Volume 2 p36: mcounteren/scounteren define availability to next lowest privilege level.
                if (priv < R5.PRIVILEGE_M) {
                    final int counteren;
                    if (priv < R5.PRIVILEGE_S) {
                        counteren = scounteren;
                    } else {
                        counteren = mcounteren;
                    }

                    // counteren[2:0] is IR, TM, CY. As such the bit index matches the masked csr value.
                    if ((counteren & (1 << (csr & 0b11))) == 0) {
                        throw new R5IllegalInstructionException();
                    }
                }
                return (int) (mcycle >> 32);
            }
            case 0xC81: { // timeh
                return (int) (rtc.getTime() >>> 32);
            }
            // 0xC83 ... 0xC9F: hpmcounter3h ... hpmcounter31h

            // Machine Information Registers
            case 0xF11: { // mvendorid, Vendor ID.
                return 0; // Not implemented.
            }
            case 0xF12: { // marchid, Architecture ID.
                return 0; // Not implemented.
            }
            case 0xF13: { // mimpid, Implementation ID.
                return 0; // Not implemented.
            }
            case 0xF14: { // mhartid, Hardware thread ID.
                return 0; // Single, primary hart.
            }

            default: {
                throw new R5IllegalInstructionException();
            }
        }
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private boolean writeCSR(final int csr, final int value) throws R5IllegalInstructionException {
        switch (csr) {
            // Floating-Point Control and Status Registers
//            case 0x001: { // fflags, Floating-Point Accrued Exceptions.
//                fflags = (byte) (value & 0b11111);
//            }
//            case 0x002: { // frm, Floating-Point Dynamic Rounding Mode.
//                frm = (byte) (value & 0b111);
//                if (frm >= 5) frm = 0; // TODO Not to spec; should store and raise invalid instruction on FP ops.
//            }
//            case 0x003: { // fcsr, Floating-Point Control and Status Register (frm + fflags).
//                frm = (byte) ((value >>> 5) & 0b111);
//                if (frm >= 5) frm = 0; // TODO Not to spec; should store and raise invalid instruction on FP ops.
//                fflags = (byte) (value & 0b11111);
//                break;
//            }

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
            case 0x100: { // sstatus, Supervisor status register.
                setStatus((mstatus & ~SSTATUS_MASK) | (value & SSTATUS_MASK));
                break;
            }
            // 0x102: sedeleg, Supervisor exception delegation register.
            // 0x103: sideleg, Supervisor interrupt delegation register.
            case 0x104: { // sie, Supervisor interrupt-enable register.
                final int mask = mideleg; // Can only set stuff that's delegated to S mode.
                mie = (mie & ~mask) | (value & mask);
                break;
            }
            case 0x105: { // stvec, Supervisor trap handler base address.
                if ((value & 0b11) < 2) { // Don't allow reserved modes.
                    stvec = value;
                }
                break;
            }
            case 0x106: { // scounteren, Supervisor counter enable.
                scounteren = value & COUNTEREN_MASK;
                break;
            }

            // Supervisor Trap Handling
            case 0x140: { // sscratch Scratch register for supervisor trap handlers.
                sscratch = value;
                break;
            }
            case 0x141: { // sepc Supervisor exception program counter.
                sepc = value & ~0b1;
                break;
            }
            case 0x142: { // scause Supervisor trap cause.
                scause = value;
                break;
            }
            case 0x143: { // stval Supervisor bad address or instruction.
                stval = value;
                break;
            }
            case 0x144: { // sip Supervisor interrupt pending.
                final int mask = mideleg; // Can only set stuff that's delegated to S mode.
                mip.updateAndGet(operand -> (operand & ~mask) | (value & mask));
                break;
            }

            // Supervisor Protection and Translation
            case 0x180: { // satp Supervisor address translation and protection.
                final int validatedValue = value & ~R5.SATP_ASID_MASK; // Say no to ASID (not implemented).
                final int change = satp ^ validatedValue;
                if ((change & (R5.SATP_MODE_MASK | R5.SATP_PPN_MASK)) != 0) {
                    if (priv == R5.PRIVILEGE_S && (mstatus & R5.STATUS_TVM_MASK) != 0) {
                        throw new R5IllegalInstructionException();
                    }

                    satp = validatedValue;
                    flushTLB();

                    return true; // Invalidate fetch cache.
                }
                break;
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
            case 0x300: { // mstatus Machine status register.
                setStatus(value & MSTATUS_MASK);
                break;
            }
            case 0x301: { // misa ISA and extensions
                break; // We do not support changing feature sets dynamically.
            }
            case 0x302: { // medeleg Machine exception delegation register.
                // From Volume 2 p31: For exceptions that cannot occur in less privileged modes, the corresponding
                // medeleg bits should be hardwired to zero. In particular, medeleg[11] is hardwired to zero.
                medeleg = value & ~(1 << R5.EXCEPTION_MACHINE_ECALL);
                break;
            }
            case 0x303: { // mideleg Machine interrupt delegation register.
                final int mask = R5.SSIP_MASK | R5.STIP_MASK | R5.SEIP_MASK;
                mideleg = (mideleg & ~mask) | (value & mask);
                break;
            }
            case 0x304: { // mie Machine interrupt-enable register.
                final int mask = R5.MTIP_MASK | R5.MSIP_MASK | R5.SEIP_MASK | R5.STIP_MASK | R5.SSIP_MASK;
                mie = (mie & ~mask) | (value & mask);
                break;
            }
            case 0x305: { // mtvec Machine trap-handler base address.
                if ((value & 0b11) < 2) { // Don't allow reserved modes.
                    mtvec = value;
                }
            }
            case 0x306: { // mcounteren Machine counter enable.
                mcounteren = value & COUNTEREN_MASK;
                break;
            }
            case 0x310: { // mstatush Additional machine status register, RV32 only.
                if (((value ^ mstatush) & R5.STATUSH_MPV_MASK) != 0) {
                    flushTLB();
                }

                mstatush = value & (R5.STATUSH_MPV_MASK | R5.STATUSH_GVA_MASK);
                break;
            }

            // Debug/Trace Registers
            case 0x7A0: { // tselect
                break;
            }
            case 0x7A1: { // tdata1
                break;
            }
            case 0x7A2: { // tdata2
                break;
            }
            case 0x7A3: { // tdata3
                break;
            }

            // Machine Trap Handling
            case 0x340: { // mscratch Scratch register for machine trap handlers.
                mscratch = value;
                break;
            }
            case 0x341: { // mepc Machine exception program counter.
                mepc = value & ~0b1; // p38: Lowest bit must always be zero.
                break;
            }
            case 0x342: { // mcause Machine trap cause.
                mcause = value;
                break;
            }
            case 0x343: { // mtval Machine bad address or instruction.
                mtval = value;
                break;
            }
            case 0x344: { // mip Machine interrupt pending.
                // p32: MEIP, MTIP, MSIP are readonly in mip.
                // Additionally, SEIP is controlled by a PLIC in our case, so we must not allow
                // software to reset it, as this could lead to lost interrupts.
                final int mask = R5.STIP_MASK | R5.SSIP_MASK;
                mip.updateAndGet(operand -> (operand & ~mask) | (value & mask));
                break;
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

            default: {
                throw new R5IllegalInstructionException();
            }
        }

        return false;
    }

    private void setStatus(final int value) {
        final int change = mstatus ^ value;
        final boolean mmuConfigChanged =
                (change & (R5.STATUS_MPRV_MASK | R5.STATUS_SUM_MASK | R5.STATUS_MXR_MASK)) != 0 ||
                ((mstatus & R5.STATUS_MPRV_MASK) != 0 && (change & R5.STATUS_MPP_MASK) != 0);
        if (mmuConfigChanged) {
            flushTLB();
        }

        final boolean dirty = ((mstatus & R5.STATUS_FS_MASK) == R5.STATUS_FS_MASK) ||
                              ((mstatus & R5.STATUS_XS_MASK) == R5.STATUS_XS_MASK);
        mstatus = (mstatus & ~R5.STATUS_SD_MASK) | (dirty ? R5.STATUS_SD_MASK : 0);

        final int mask = MSTATUS_MASK & ~R5.STATUS_FS_MASK;
        mstatus = (mstatus & ~mask) | (value & mask);
    }

    private void setPrivilege(final int level) {
        if (priv == level) {
            return;
        }

        flushTLB();

        priv = level;
    }

    private void raiseException(final int exception, final int value) {
        // Exceptions take cycle.
        mcycle++;

        // Check whether to run supervisor level trap instead of machine level one.
        // We don't implement the N extension (user level interrupts) so if we're
        // currently in S or U privilege level we'll run the S level trap handler
        // either way -- assuming that the current interrupt/exception is allowed
        // to be delegated by M level.
        final boolean async = (exception & R5.INTERRUPT) != 0;
        final int cause = exception & ~R5.INTERRUPT;
        final int deleg = async ? mideleg : medeleg;

        // Was interrupt for current priv level enabled? There are cases we can
        // get here even for interrupts! Specifically when an M level interrupt
        // is raised while in S mode. This will get here even if M level interrupt
        // enabled bit is zero, as per spec (Volume 2 p21).
        final int oldIE = (mstatus >>> priv) & 0b1;

        final int vec;
        if (priv <= R5.PRIVILEGE_S && ((deleg >>> cause) & 0b1) != 0) {
            scause = exception;
            sepc = pc;
            stval = value;
            mstatus = (mstatus & ~R5.STATUS_SPIE_MASK) |
                      (oldIE << R5.STATUS_SPIE_SHIFT);
            mstatus = (mstatus & ~R5.STATUS_SPP_MASK) |
                      (priv << R5.STATUS_SPP_SHIFT);
            mstatus &= ~R5.STATUS_SIE_MASK;
            setPrivilege(R5.PRIVILEGE_S);
            vec = stvec;
        } else {
            mcause = exception;
            mepc = pc;
            mtval = value;
            mstatus = (mstatus & ~R5.STATUS_MPIE_MASK) |
                      (oldIE << R5.STATUS_MPIE_SHIFT);
            mstatus = (mstatus & ~R5.STATUS_MPP_MASK) |
                      (priv << R5.STATUS_MPP_SHIFT);
            mstatus &= ~R5.STATUS_MIE_MASK;
            setPrivilege(R5.PRIVILEGE_M);
            vec = mtvec;
        }

        final int mode = vec & 0b11;
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

    private void raiseException(final int cause) {
        raiseException(cause, 0);
    }

    private void raiseInterrupt(final int pending) {
        final boolean mieEnabled = (mstatus & R5.STATUS_MIE_MASK) != 0;
        final boolean sieEnabled = (mstatus & R5.STATUS_SIE_MASK) != 0;

        // V2p21: interrupts handled by a higher privilege level, i.e. that are not delegated
        // to a lower privilege level, will always fire -- even if their global flag is false!
        final int mieMask = priv < R5.PRIVILEGE_M || mieEnabled ? 0xFFFFFFFF : 0;
        final int sieMask = priv < R5.PRIVILEGE_S || (priv == R5.PRIVILEGE_S && sieEnabled) ? 0xFFFFFFFF : 0;

        final int interrupts = (pending & ~mideleg & mieMask) | (pending & mideleg & sieMask);
        if (interrupts != 0) {
            // TODO p33: Interrupt order is handled in decreasing order of privilege mode,
            //      and inside a single privilege mode in order E,S,T.
            final int interrupt = Integer.numberOfTrailingZeros(interrupts);
            raiseException(interrupt | R5.INTERRUPT);
        }
    }

    private byte load8(final int address) throws MemoryAccessException {
        return (byte) loadx(address, Sizes.SIZE_8, Sizes.SIZE_8_LOG2);
    }

    private void store8(final int address, final byte value) throws MemoryAccessException {
        storex(address, value, Sizes.SIZE_8, Sizes.SIZE_8_LOG2);
    }

    private short load16(final int address) throws MemoryAccessException {
        return (short) loadx(address, Sizes.SIZE_16, Sizes.SIZE_16_LOG2);
    }

    private void store16(final int address, final short value) throws MemoryAccessException {
        storex(address, value, Sizes.SIZE_16, Sizes.SIZE_16_LOG2);
    }

    private int load32(final int address) throws MemoryAccessException {
        return loadx(address, Sizes.SIZE_32, Sizes.SIZE_32_LOG2);
    }

    private void store32(final int address, final int value) throws MemoryAccessException {
        storex(address, value, Sizes.SIZE_32, Sizes.SIZE_32_LOG2);
    }

    private R5CPUTLBEntry fetchPage(final int address) throws MemoryAccessException {
        if ((address & 1) != 0) {
            throw new MemoryAccessException(address, MemoryAccessException.Type.MISALIGNED_FETCH);
        }

        final int index = (address >>> R5.PAGE_ADDRESS_SHIFT) & (TLB_SIZE - 1);
        final int hash = address & ~R5.PAGE_ADDRESS_MASK;
        final R5CPUTLBEntry entry = fetchTLB[index];
        if (entry.hash == hash) {
            return entry;
        } else {
            return fetchPageSlow(address);
        }
    }

    private int loadx(final int address, final int size, final int sizeLog2) throws MemoryAccessException {
        final int index = (address >>> R5.PAGE_ADDRESS_SHIFT) & (TLB_SIZE - 1);
        final int alignment = size / 8; // Enforce aligned memory access.
        final int alignmentMask = alignment - 1;
        final int hash = address & ~(R5.PAGE_ADDRESS_MASK & ~alignmentMask);
        final R5CPUTLBEntry entry = loadTLB[index];
        if (entry.hash == hash) {
            return entry.device.load(address + entry.toOffset, sizeLog2);
        } else {
            return loadSlow(address, sizeLog2);
        }
    }

    private void storex(final int address, final int value, final int size, final int sizeLog2) throws MemoryAccessException {
        final int index = (address >>> R5.PAGE_ADDRESS_SHIFT) & (TLB_SIZE - 1);
        final int alignment = size / 8; // Enforce aligned memory access.
        final int alignmentMask = alignment - 1;
        final int hash = address & ~(R5.PAGE_ADDRESS_MASK & ~alignmentMask);
        final R5CPUTLBEntry entry = storeTLB[index];
        if (entry.hash == hash) {
            entry.device.store(address + entry.toOffset, value, sizeLog2);
        } else {
            storeSlow(address, value, sizeLog2);
        }
    }

    private R5CPUTLBEntry fetchPageSlow(final int address) throws MemoryAccessException {
        final int physicalAddress = getPhysicalAddress(address, R5MemoryAccessType.FETCH);
        final MemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
        if (range == null || !(range.device instanceof PhysicalMemory)) {
            throw new MemoryAccessException(address, MemoryAccessException.Type.FETCH_FAULT);
        }

        return updateTLB(fetchTLB, address, physicalAddress, range);
    }

    private int loadSlow(final int address, final int sizeLog2) throws MemoryAccessException {
        final int size = 1 << sizeLog2;
        final int alignment = address & (size - 1);
        if (alignment != 0) {
            throw new MemoryAccessException(address, MemoryAccessException.Type.MISALIGNED_LOAD);
        } else {
            final int physicalAddress = getPhysicalAddress(address, R5MemoryAccessType.LOAD);
            final MemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
            if (range == null) {
                LOGGER.debug("Trying to load from invalid physical address [{}].", address);
                return 0;
            } else if (range.device instanceof PhysicalMemory) {
                final R5CPUTLBEntry entry = updateTLB(loadTLB, address, physicalAddress, range);
                return entry.device.load(address + entry.toOffset, sizeLog2);
            } else {
                return range.device.load(physicalAddress - range.start, sizeLog2);
            }
        }
    }

    private void storeSlow(final int address, final int value, final int sizeLog2) throws MemoryAccessException {
        final int size = 1 << sizeLog2;
        final int alignment = address & (size - 1);
        if (alignment != 0) {
            throw new MemoryAccessException(address, MemoryAccessException.Type.MISALIGNED_STORE);
        } else {
            final int physicalAddress = getPhysicalAddress(address, R5MemoryAccessType.STORE);
            final MemoryRange range = physicalMemory.getMemoryRange(physicalAddress);
            if (range == null) {
                LOGGER.debug("Trying to store to invalid physical address [{}].", address);
            } else if (range.device instanceof PhysicalMemory) {
                final R5CPUTLBEntry entry = updateTLB(storeTLB, address, physicalAddress, range);
                final int offset = address + entry.toOffset;
                entry.device.store(offset, value, sizeLog2);
                physicalMemory.setDirty(range, offset);
            } else {
                range.device.store(physicalAddress - range.start, value, sizeLog2);
            }
        }
    }

    private int getPhysicalAddress(final int virtualAddress, final R5MemoryAccessType accessType) throws MemoryAccessException {
        final int privilege;
        if ((mstatus & R5.STATUS_MPRV_MASK) != 0 && accessType != R5MemoryAccessType.FETCH) {
            privilege = (mstatus & R5.STATUS_MPP_MASK) >>> R5.STATUS_MPP_SHIFT;
        } else {
            privilege = this.priv;
        }

        if (privilege == R5.PRIVILEGE_M) {
            return virtualAddress;
        }

        if ((satp & R5.SATP_MODE_MASK) == 0) {
            return virtualAddress;
        }

        // Virtual address structure:  VPN1[31:22], VPN0[21:12], page offset[11:0]
        // Physical address structure: PPN1[33:22], PPN0[21:12], page offset[11:0]
        // Page table entry structure: PPN1[31:20], PPN0[19:10], RSW[9:8], D, A, G, U, X, W, R, V

        // Virtual address translation, V2p75f.
        int pteAddress = (satp & R5.SATP_PPN_MASK) << R5.PAGE_ADDRESS_SHIFT; // 1.
        for (int i = R5.SV32_LEVELS - 1; i >= 0; i--) { // 2.
            final int vpnShift = R5.PAGE_ADDRESS_SHIFT + R5.SV32_XPN_SIZE * i;
            final int vpn = (virtualAddress >>> vpnShift) & R5.SV32_XPN_MASK;
            pteAddress += vpn << R5.SV32_PTE_SIZE_LOG2; // equivalent to vpn * PTE size
            int pte = physicalMemory.load(pteAddress, Sizes.SIZE_32_LOG2); // 3.

            if ((pte & R5.PTE_V_MASK) == 0 || ((pte & R5.PTE_R_MASK) == 0 && (pte & R5.PTE_W_MASK) != 0)) { // 4.
                throw getPageFaultException(accessType, virtualAddress);
            }

            int xwr = pte & (R5.PTE_X_MASK | R5.PTE_W_MASK | R5.PTE_R_MASK);
            if (xwr == 0) { // 5.
                final int ppn = pte >>> R5.PTE_DATA_BITS;
                pteAddress = ppn << R5.PAGE_ADDRESS_SHIFT;
                continue;
            }

            // 6. Leaf node, do access permission checks.

            // Check reserved/invalid configurations.
            if ((xwr & R5.PTE_R_MASK) == 0 && (xwr & R5.PTE_W_MASK) != 0) {
                throw getPageFaultException(accessType, virtualAddress);
            }

            // Check privilege. Can only be in S or U mode here, M was handled above. V2p61.
            final int userModeFlag = pte & R5.PTE_U_MASK;
            if (privilege == R5.PRIVILEGE_S) {
                if (userModeFlag != 0 &&
                    (accessType == R5MemoryAccessType.FETCH || (mstatus & R5.STATUS_SUM_MASK) == 0))
                    throw getPageFaultException(accessType, virtualAddress);
            } else if (userModeFlag == 0) {
                throw getPageFaultException(accessType, virtualAddress);
            }

            // MXR allows read on execute-only pages.
            if ((mstatus & R5.STATUS_MXR_MASK) != 0) {
                xwr |= R5.PTE_R_MASK;
            }

            // Check access flags.
            if ((xwr & accessType.mask) == 0) {
                throw getPageFaultException(accessType, virtualAddress);
            }

            // 7. Check misaligned superpage.
            if (i > 0) {
                final int ppnLSB = (pte >>> R5.PTE_DATA_BITS) & R5.SV32_XPN_MASK;
                if (ppnLSB != 0) {
                    throw getPageFaultException(accessType, virtualAddress);
                }
            }

            // 8. Update accessed and dirty flags.
            if ((pte & R5.PTE_A_MASK) == 0 ||
                (accessType == R5MemoryAccessType.STORE && (pte & R5.PTE_D_MASK) == 0)) {
                pte |= R5.PTE_A_MASK;
                if (accessType == R5MemoryAccessType.STORE) {
                    pte |= R5.PTE_D_MASK;
                }

                physicalMemory.store(pteAddress, pte, 2);
            }

            // 9. physical address = pte.ppn[LEVELS-1:i], va.vpn[i-1:0], va.pgoff
            final int vpnAndPageOffsetMask = (1 << vpnShift) - 1;
            final int ppn = (pte >>> R5.PTE_DATA_BITS) << R5.PAGE_ADDRESS_SHIFT;
            return (ppn & ~vpnAndPageOffsetMask) | (virtualAddress & vpnAndPageOffsetMask);
        }

        throw getPageFaultException(accessType, virtualAddress);
    }

    private static MemoryAccessException getPageFaultException(final R5MemoryAccessType accessType, final int address) {
        switch (accessType) {
            case LOAD:
                return new MemoryAccessException(address, MemoryAccessException.Type.LOAD_PAGE_FAULT);
            case STORE:
                return new MemoryAccessException(address, MemoryAccessException.Type.STORE_PAGE_FAULT);
            case FETCH:
                return new MemoryAccessException(address, MemoryAccessException.Type.FETCH_PAGE_FAULT);
            default:
                throw new AssertionError();
        }
    }

    private static R5CPUTLBEntry updateTLB(final R5CPUTLBEntry[] tlb, final int address, final int physicalAddress, final MemoryRange range) {
        final int index = (address >>> R5.PAGE_ADDRESS_SHIFT) & (TLB_SIZE - 1);
        final int hash = address & ~R5.PAGE_ADDRESS_MASK;

        final R5CPUTLBEntry entry = tlb[index];
        entry.hash = hash;
        entry.toOffset = physicalAddress - address - range.start;
        entry.device = range.device;

        return entry;
    }

    private void flushTLB() {
        // Only reset the most necessary field, the hash (which we use to check if an entry is applicable).
        // Reset per-array for *much* faster clears due to it being a faster memory access pattern/the
        // hotspot optimizer being able to more efficiently handle it (probably the latter, I suspect this
        // gets replaced by a memset with stride).
        for (int i = 0; i < TLB_SIZE; i++) {
            fetchTLB[i].hash = -1;
        }
        for (int i = 0; i < TLB_SIZE; i++) {
            loadTLB[i].hash = -1;
        }
        for (int i = 0; i < TLB_SIZE; i++) {
            storeTLB[i].hash = -1;
        }
    }

    private void flushTLB(final int address) {
        flushTLB();
    }

    ///////////////////////////////////////////////////////////////////
    // RV32I Base Instruction Set

    @Instruction("LUI")
    private void lui(@Field("rd") final int rd,
                     @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = imm;
        }
    }

    @Instruction("AUIPC")
    private void auipc(@Field("rd") final int rd,
                       @Field("imm") final int imm,
                       @ProgramCounter final int pc) {
        if (rd != 0) {
            x[rd] = pc + imm;
        }
    }

    @Instruction("JAL")
    private void jal(@Field("rd") final int rd,
                     @Field("imm") final int imm,
                     @ProgramCounter final int pc,
                     @InstructionSize final int instructionSize) {
        if (rd != 0) {
            x[rd] = pc + instructionSize;
        }

        this.pc = pc + imm;
    }

    @Instruction("JALR")
    private void jalr(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm,
                      @ProgramCounter final int pc,
                      @InstructionSize final int instructionSize) {
        // Compute first in case rs1 == rd and force alignment.
        final int address = (x[rs1] + imm) & ~1;
        if (rd != 0) {
            x[rd] = pc + instructionSize;
        }

        this.pc = address;
    }

    @Instruction("BEQ")
    private boolean beq(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final int pc) {
        if (x[rs1] == x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("BNE")
    private boolean bne(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final int pc) {
        if (x[rs1] != x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("BLT")
    private boolean blt(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final int pc) {
        if (x[rs1] < x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("BGE")
    private boolean bge(@Field("rs1") final int rs1,
                        @Field("rs2") final int rs2,
                        @Field("imm") final int imm,
                        @ProgramCounter final int pc) {
        if (x[rs1] >= x[rs2]) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("BLTU")
    private boolean bltu(@Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("imm") final int imm,
                         @ProgramCounter final int pc) {
        if (Integer.compareUnsigned(x[rs1], x[rs2]) < 0) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @Instruction("BGEU")
    private boolean bgeu(@Field("rs1") final int rs1,
                         @Field("rs2") final int rs2,
                         @Field("imm") final int imm,
                         @ProgramCounter final int pc) {
        if (Integer.compareUnsigned(x[rs1], x[rs2]) >= 0) {
            this.pc = pc + imm;
            return true;
        } else {
            return false;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("LB")
    private void lb(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws MemoryAccessException {
        final int result = load8(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("LH")
    private void lh(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws MemoryAccessException {
        final int result = load16(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("LW")
    private void lw(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("imm") final int imm) throws MemoryAccessException {
        final int result = load32(x[rs1] + imm);
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("LBU")
    private void lbu(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws MemoryAccessException {
        final int result = load8(x[rs1] + imm) & 0xFF;
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("LHU")
    private void lhu(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) throws MemoryAccessException {
        final int result = load16(x[rs1] + imm) & 0xFFFF;
        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("SB")
    private void sb(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws MemoryAccessException {
        store8(x[rs1] + imm, (byte) x[rs2]);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("SH")
    private void sh(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws MemoryAccessException {
        store16(x[rs1] + imm, (short) x[rs2]);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("SW")
    private void sw(@Field("rs1") final int rs1,
                    @Field("rs2") final int rs2,
                    @Field("imm") final int imm) throws MemoryAccessException {
        store32(x[rs1] + imm, x[rs2]);
    }

    @Instruction("ADDI")
    private void addi(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] + imm;
        }
    }

    @Instruction("SLTI")
    private void slti(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] < imm ? 1 : 0;
        }
    }

    @Instruction("SLTIU")
    private void sltiu(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = Integer.compareUnsigned(x[rs1], imm) < 0 ? 1 : 0;
        }
    }

    @Instruction("XORI")
    private void xori(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] ^ imm;
        }
    }

    @Instruction("ORI")
    private void ori(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] | imm;
        }
    }

    @Instruction("ANDI")
    private void andi(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("imm") final int imm) {
        if (rd != 0) {
            x[rd] = x[rs1] & imm;
        }
    }

    @Instruction("SLLI")
    private void slli(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] << (shamt & 0b11111);
        }
    }

    @Instruction("SRLI")
    private void srli(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] >>> (shamt & 0b11111);
        }
    }

    @Instruction("SRAI")
    private void srai(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("shamt") final int shamt) {
        if (rd != 0) {
            x[rd] = x[rs1] >> (shamt & 0b11111);
        }
    }

    @Instruction("ADD")
    private void add(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] + x[rs2];
        }
    }

    @Instruction("SUB")
    private void sub(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] - x[rs2];
        }
    }

    @Instruction("SLL")
    private void sll(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] << x[rs2];
        }
    }

    @Instruction("SLT")
    private void slt(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] < x[rs2] ? 1 : 0;
        }
    }

    @Instruction("SLTU")
    private void sltu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = Integer.compareUnsigned(x[rs1], x[rs2]) < 0 ? 1 : 0;
        }
    }

    @Instruction("XOR")
    private void xor(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] ^ x[rs2];
        }
    }

    @Instruction("SRL")
    private void srl(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] >>> x[rs2];
        }
    }

    @Instruction("SRA")
    private void sra(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] >> x[rs2];
        }
    }

    @Instruction("OR")
    private void or(@Field("rd") final int rd,
                    @Field("rs1") final int rs1,
                    @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] | x[rs2];
        }
    }

    @Instruction("AND")
    private void and(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] & x[rs2];
        }
    }

    @Instruction("FENCE")
    private void fence() {
        // no-op
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("ECALL")
    private void ecall(@ProgramCounter final int pc) {
        this.pc = pc;
        raiseException(R5.EXCEPTION_USER_ECALL + priv);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("EBREAK")
    private void ebreak(@ProgramCounter final int pc) {
        this.pc = pc;
        raiseException(R5.EXCEPTION_BREAKPOINT);
    }

    ///////////////////////////////////////////////////////////////////
    // RV32/RV64 Zifencei Standard Extension

    @Instruction("FENCE.I")
    private void fence_i() {
        // no-op
    }

    ///////////////////////////////////////////////////////////////////
    // RV32/RV64 Zicsr Standard Extension

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRW")
    private boolean csrrw(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrw_impl(rd, x[rs1], csr);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRS")
    private boolean csrrs(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrx_impl(rd, rs1, csr, x[rs1], true);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRC")
    private boolean csrrc(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrx_impl(rd, rs1, csr, x[rs1], false);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRWI")
    private boolean csrrwi(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrw_impl(rd, rs1, csr);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRSI")
    private boolean csrrsi(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrx_impl(rd, rs1, csr, rs1, true);
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("CSRRCI")
    private boolean csrrci(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("csr") final int csr) throws R5IllegalInstructionException {
        return csrrx_impl(rd, rs1, csr, rs1, false);
    }

    ///////////////////////////////////////////////////////////////////
    // RV32M Standard Extension

    @Instruction("MUL")
    private void mul(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = x[rs1] * x[rs2];
        }
    }

    @Instruction("MULH")
    private void mulh(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) (((long) x[rs1] * (long) x[rs2]) >> 32);
        }
    }

    @Instruction("MULHSU")
    private void mulhsu(@Field("rd") final int rd,
                        @Field("rs1") final int rs1,
                        @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) (((long) x[rs1] * Integer.toUnsignedLong(x[rs2])) >> 32);
        }
    }

    @Instruction("MULHU")
    private void mulhu(@Field("rd") final int rd,
                       @Field("rs1") final int rs1,
                       @Field("rs2") final int rs2) {
        if (rd != 0) {
            x[rd] = (int) ((Integer.toUnsignedLong(x[rs1]) * Integer.toUnsignedLong(x[rs2])) >>> 32);
        }
    }

    @Instruction("DIV")
    private void div(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = -1;
            } else if (x[rs1] == Integer.MIN_VALUE && x[rs2] == -1) {
                x[rd] = x[rs1];
            } else {
                x[rd] = x[rs1] / x[rs2];
            }
        }
    }

    @Instruction("DIVU")
    private void divu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = -1;
            } else {
                x[rd] = Integer.divideUnsigned(x[rs1], x[rs2]);
            }
        }
    }

    @Instruction("REM")
    private void rem(@Field("rd") final int rd,
                     @Field("rs1") final int rs1,
                     @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = x[rs1];
            } else if (x[rs1] == Integer.MIN_VALUE && x[rs2] == -1) {
                x[rd] = 0;
            } else {
                x[rd] = x[rs1] % x[rs2];
            }
        }
    }

    @Instruction("REMU")
    private void remu(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) {
        if (rd != 0) {
            if (x[rs2] == 0) {
                x[rd] = x[rs1];
            } else {
                x[rd] = Integer.remainderUnsigned(x[rs1], x[rs2]);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////
    // RV32A Standard Extension

    @ContainsNonStaticMethodInvocations
    @Instruction("LR.W")
    private void lr_w(@Field("rd") final int rd,
                      @Field("rs1") final int rs1) throws MemoryAccessException {
        final int result;
        final int address = x[rs1];
        result = load32(address);
        reservation_set = address;

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("SC.W")
    private void sc_w(@Field("rd") final int rd,
                      @Field("rs1") final int rs1,
                      @Field("rs2") final int rs2) throws MemoryAccessException {
        final int result;
        final int address = x[rs1];
        if (address == reservation_set) {
            store32(address, x[rs2]);
            result = 0;
        } else {
            result = 1;
        }

        reservation_set = -1; // Always invalidate as per spec.

        if (rd != 0) {
            x[rd] = result;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOSWAP.W")
    private void amoswap_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOADD.W")
    private void amoadd_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, a + b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOXOR.W")
    private void amoxor_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, a ^ b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOAND.W")
    private void amoand_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, a & b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOOR.W")
    private void amoor_w(@Field("rd") final int rd,
                         @Field("rs1") final int rs1,
                         @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, a | b);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOMIN.W")
    private void amomin_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, Math.min(a, b));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOMAX.W")
    private void amomax_w(@Field("rd") final int rd,
                          @Field("rs1") final int rs1,
                          @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        final int c = Math.max(a, b);
        store32(address, c);

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOMINU.W")
    private void amominu_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, (int) Math.min(Integer.toUnsignedLong(a), Integer.toUnsignedLong(b)));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("AMOMAXU.W")
    private void amomaxu_w(@Field("rd") final int rd,
                           @Field("rs1") final int rs1,
                           @Field("rs2") final int rs2) throws MemoryAccessException {
        final int address = x[rs1];
        final int a = load32(address);
        final int b = x[rs2];

        store32(address, (int) Math.max(Integer.toUnsignedLong(a), Integer.toUnsignedLong(b)));

        if (rd != 0) {
            x[rd] = a;
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Privileged Instructions

    @ContainsNonStaticMethodInvocations
    @Instruction("SRET")
    private void sret() throws R5IllegalInstructionException {
        if (priv < R5.PRIVILEGE_S) {
            throw new R5IllegalInstructionException();
        }

        if ((mstatus & R5.STATUS_TSR_MASK) != 0 && priv < R5.PRIVILEGE_M) {
            throw new R5IllegalInstructionException();
        }

        final int spp = (mstatus & R5.STATUS_SPP_MASK) >>> R5.STATUS_SPP_SHIFT; // Previous privilege level.
        final int spie = (mstatus & R5.STATUS_SPIE_MASK) >>> R5.STATUS_SPIE_SHIFT; // Preview interrupt-enable state.
        mstatus = (mstatus & ~R5.STATUS_SIE_MASK) | ((R5.STATUS_SIE_MASK * spie) << R5.STATUS_SIE_SHIFT);
        mstatus = (mstatus & ~(1 << spp)) |
                  (spie << spp);
        mstatus |= R5.STATUS_SPIE_MASK;
        mstatus &= ~R5.STATUS_SPP_MASK;
        mstatus &= ~R5.STATUS_MPRV_MASK;

        setPrivilege(spp);

        pc = sepc;
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("MRET")
    private void mret() throws R5IllegalInstructionException {
        if (priv < R5.PRIVILEGE_M) {
            throw new R5IllegalInstructionException();
        }

        final int mpp = (mstatus & R5.STATUS_MPP_MASK) >>> R5.STATUS_MPP_SHIFT; // Previous privilege level.
        final int mpie = (mstatus & R5.STATUS_MPIE_MASK) >>> R5.STATUS_MPIE_SHIFT; // Preview interrupt-enable state.
        mstatus = (mstatus & ~R5.STATUS_MIE_MASK) | ((R5.STATUS_MIE_MASK * mpie) << R5.STATUS_MIE_SHIFT);
        mstatus |= R5.STATUS_MPIE_MASK;
        mstatus &= ~R5.STATUS_MPP_MASK;
        if (mpp != R5.PRIVILEGE_M) {
            mstatus &= ~R5.STATUS_MPRV_MASK;
        }

        setPrivilege(mpp);

        pc = mepc;
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("WFI")
    private boolean wfi() throws R5IllegalInstructionException {
        if (priv == R5.PRIVILEGE_U) {
            throw new R5IllegalInstructionException();
        }
        if ((mstatus & R5.STATUS_TW_MASK) != 0 && priv == R5.PRIVILEGE_S) {
            throw new R5IllegalInstructionException();
        }

        if ((mip.get() & mie) != 0) {
            return false;
        }

        if (mie == 0) {
            LOGGER.warn("Waiting for interrupts but none are enabled.");
        }

        waitingForInterrupt = true;
        return true;
    }

    @ContainsNonStaticMethodInvocations
    @Instruction("SFENCE.VMA")
    private boolean sfence_vma(@Field("rs1") final int rs1,
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

        return true;
    }

    public R5CPUStateSnapshot getState() {
        final R5CPUStateSnapshot state = new R5CPUStateSnapshot();

        state.pc = pc;
        System.arraycopy(x, 0, state.x, 0, 32);

//        System.arraycopy(f, 0, state.f, 0, 32);
//        state.fflags = fflags;
//        state.frm = frm;

        state.reservation_set = reservation_set;

        state.mcycle = mcycle;

        state.mstatus = mstatus;
        state.mstatush = mstatush;
        state.mtvec = mtvec;
        state.medeleg = medeleg;
        state.mideleg = mideleg;
        state.mip = mip.get();
        state.mie = mie;
        state.mcounteren = mcounteren;
        state.mscratch = mscratch;
        state.mepc = mepc;
        state.mcause = mcause;
        state.mtval = mtval;

        state.stvec = stvec;
        state.scounteren = scounteren;
        state.sscratch = sscratch;
        state.sepc = sepc;
        state.scause = scause;
        state.stval = stval;
        state.satp = satp;

        state.priv = priv;
        state.waitingForInterrupt = waitingForInterrupt;

        return state;
    }
}
