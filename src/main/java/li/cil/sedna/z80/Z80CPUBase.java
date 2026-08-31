package li.cil.sedna.z80;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.debug.CPUDebugInterface;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.InstructionDefinition.*;
import li.cil.sedna.z80.exception.Z80IllegalInstructionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.LongConsumer;

/**
 * Zilog Z80 implementation.
 * <p>
 * Semantics follow the documented instruction set plus the widely relied-on undocumented parts:
 * the X/Y result flags (bits 3/5), the internal MEMPTR/WZ register where it is observable through
 * BIT, SLL, IXH/IXL register access, and the prefix fall-through behavior (a DD/FD prefix in
 * front of an opcode it does not apply to leaves the opcode's behavior unmodified).
 * <p>
 * Timing is counted in T-states per instruction, without per-machine-cycle bus granularity.
 * Known deviations: a DD/FD prefix in front of an opcode it does not modify does not add its 4T;
 * SCF/CCF X/Y are computed from A alone (the Q register is not modeled).
 * <p>
 * Unmapped memory and port addresses read as 0xFF and ignore writes. Instruction fetch always
 * reads four bytes, so memory-mapped devices with read side effects should not be mapped adjacent
 * to executable memory; that's what the I/O map is for.
 */
@Serialized
public abstract class Z80CPUBase implements Z80CPU {
    private static final Logger LOGGER = LogManager.getLogger(Z80CPUBase.class);

    private static final int FLAG_S = 0x80;
    private static final int FLAG_Z = 0x40;
    private static final int FLAG_Y = 0x20;
    private static final int FLAG_H = 0x10;
    private static final int FLAG_X = 0x08;
    private static final int FLAG_P = 0x04;
    private static final int FLAG_N = 0x02;
    private static final int FLAG_C = 0x01;

    private static final int B = 0, C = 1, D = 2, E = 3, H = 4, L = 5, A = 7;

    // ------------------------------------------------------------- //
    // State. Package-visible for tests; the board talks through the interface.

    final int[] r = new int[8]; // B C D E H L (6 unused) A
    int f;
    final int[] r2 = new int[8]; // Shadow set.
    int f2;
    final int[] ixiy = new int[2];
    int sp;
    protected long pc;
    int i;
    int rr; // R (refresh) register.
    boolean iff1, iff2;
    int im;
    int memptr; // Internal WZ register, observable through the X/Y flags of BIT n,(HL).
    boolean halted;
    boolean eiDelay; // Interrupts are accepted only after the instruction following EI.

    // Interrupt lines; volatile so devices may raise them from other threads, matching the R5
    // core's cross-thread raise contract. raiseInterrupt writes irqData before irqRequested and
    // step() reads them in the opposite order.
    volatile boolean irqRequested;
    volatile int irqData;
    volatile boolean nmiRequested;

    protected long cycles; // T-states executed.
    private int cycleDebt;
    private transient boolean debugStop;

    protected transient long cycleLimit;
    private transient int frequency = 4_000_000;
    private transient MappedMemoryRange cachedRange;
    private transient boolean im0WarningLogged;

    protected final transient DebugInterface debugInterface = new DebugInterface();

    private final transient MemoryMap memoryMap;
    private final transient MemoryMap ioMap;

    Z80CPUBase(final MemoryMap memoryMap, final MemoryMap ioMap) {
        this.memoryMap = memoryMap;
        this.ioMap = ioMap;
        reset();
    }

    // ------------------------------------------------------------- //
    // Z80CPU

    @Override
    public void reset() {
        reset(true, 0x0000);
    }

    @Override
    public void reset(final boolean hard, final int pc) {
        this.pc = pc & 0xFFFF;
        iff1 = false;
        iff2 = false;
        im = 0;
        i = 0;
        rr = 0;
        halted = false;
        eiDelay = false;
        irqRequested = false;
        nmiRequested = false;

        if (hard) {
            for (int j = 0; j < 8; j++) {
                r[j] = 0;
                r2[j] = 0;
            }
            r[A] = 0xFF;
            f = 0xFF;
            f2 = 0;
            ixiy[0] = 0;
            ixiy[1] = 0;
            sp = 0xFFFF;
            memptr = 0;
            cycles = 0;
            cycleDebt = 0;
        }
    }

    @Override
    public void invalidateCaches() {
        cachedRange = null;
    }

    @Override
    public int getFrequency() {
        return frequency;
    }

    @Override
    public void setFrequency(final int value) {
        frequency = value;
    }

    @Override
    public long getCycles() {
        return cycles;
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    @Override
    public CPUDebugInterface getDebugInterface() {
        return debugInterface;
    }

    @Override
    public void raiseInterrupt(final int data) {
        irqData = data & 0xFF;
        irqRequested = true;
    }

    @Override
    public void lowerInterrupt() {
        irqRequested = false;
    }

    @Override
    public void raiseNMI() {
        nmiRequested = true;
    }

    @Override
    public void step(int cycles) {
        debugStop = false;

        final int paidDebt = Math.min(cycles, cycleDebt);
        cycles -= paidDebt;
        cycleDebt -= paidDebt;

        final long limit = this.cycles + cycles;
        cycleLimit = limit;
        while (this.cycles < limit) {
            if (nmiRequested) {
                acceptNMI();
            } else if (irqRequested && iff1 && !eiDelay) {
                acceptIRQ();
            }

            if (halted) {
                // The halted CPU keeps issuing M1 (no-op) cycles, so R keeps advancing.
                rr = (rr & 0x80) | ((rr + (int) ((limit - this.cycles) >> 2)) & 0x7F);
                this.cycles = limit;
                break;
            }

            final boolean singleStep = eiDelay;
            eiDelay = false;
            interpretTrace(singleStep, debugInterface.breakpoints.isEmpty() ? null : debugInterface.breakpoints);

            if (debugStop) {
                eiDelay |= singleStep;
                pc &= 0xFFFF;
                return;
            }
        }

        pc &= 0xFFFF;
        cycleDebt += (int) (this.cycles - limit);
    }

    protected abstract void interpretTrace(final boolean singleStep, final LongSet breakpoints);

    protected static Z80IllegalInstructionException illegalInstruction() {
        return new Z80IllegalInstructionException();
    }

    protected final boolean interruptsPending() {
        return nmiRequested || (irqRequested && iff1);
    }

    /**
     * R advances once per M1 cycle: once per instruction, twice for prefixed ones. Bit 7 is only
     * ever written by LD R,A.
     */
    protected final void bumpR(final int inst) {
        final int b0 = inst & 0xFF;
        final int m1 = (b0 == 0xCB || b0 == 0xED || (b0 & 0xDF) == 0xDD) ? 2 : 1;
        rr = (rr & 0x80) | ((rr + m1) & 0x7F);
    }

    // ------------------------------------------------------------- //
    // Interrupt acceptance

    private void acceptNMI() {
        nmiRequested = false;
        halted = false;
        // IFF2 is deliberately left unchanged: it holds the pre-NMI interrupt-enable state
        // (mirroring IFF1 since the last EI/DI) and is what RETN restores from.
        iff1 = false;
        bumpM1();
        push16((int) pc);
        pc = 0x0066;
        memptr = 0x0066;
        cycles += 11;
    }

    private void acceptIRQ() {
        halted = false;
        iff1 = false;
        iff2 = false;
        bumpM1();
        switch (im) {
            case 2 -> {
                push16((int) pc);
                final int vector = ((i << 8) | irqData) & 0xFFFF;
                pc = load16(vector);
                memptr = (int) pc;
                cycles += 19;
            }
            case 1 -> {
                push16((int) pc);
                pc = 0x0038;
                memptr = 0x0038;
                cycles += 13;
            }
            default -> {
                // IM 0 executes the byte placed on the bus; in practice that is an RST.
                final int data = irqData;
                if ((data & 0xC7) == 0xC7) {
                    push16((int) pc);
                    pc = data & 0x38;
                } else {
                    if (!im0WarningLogged) {
                        im0WarningLogged = true;
                        LOGGER.warn("Unsupported IM 0 interrupt opcode [{}], treating as RST 38h.", data);
                    }
                    push16((int) pc);
                    pc = 0x0038;
                }
                memptr = (int) pc;
                cycles += 13;
            }
        }
    }

    private void bumpM1() {
        rr = (rr & 0x80) | ((rr + 1) & 0x7F);
    }

    // ------------------------------------------------------------- //
    // Bus access

    protected final int fetch32(final int address) {
        return load8(address)
                | (load8(address + 1) << 8)
                | (load8(address + 2) << 16)
                | (load8(address + 3) << 24);
    }

    private int load8(int address) {
        address &= 0xFFFF;
        MappedMemoryRange range = cachedRange;
        if (range == null || address < range.start || address > range.end) {
            range = memoryMap.getMemoryRange(address);
            if (range == null) {
                return 0xFF;
            }
            cachedRange = range;
        }
        try {
            return (int) range.device.load((int) (address - range.start), Sizes.SIZE_8_LOG2) & 0xFF;
        } catch (final MemoryAccessException e) {
            return 0xFF;
        }
    }

    private void store8(int address, final int value) {
        address &= 0xFFFF;
        MappedMemoryRange range = cachedRange;
        if (range == null || address < range.start || address > range.end) {
            range = memoryMap.getMemoryRange(address);
            if (range == null) {
                return;
            }
            cachedRange = range;
        }
        try {
            range.device.store((int) (address - range.start), value & 0xFF, Sizes.SIZE_8_LOG2);
        } catch (final MemoryAccessException ignored) {
        }
    }

    private int load16(final int address) {
        return load8(address) | (load8(address + 1) << 8);
    }

    private void store16(final int address, final int value) {
        store8(address, value);
        store8(address + 1, value >>> 8);
    }

    private void push16(final int value) {
        sp = (sp - 1) & 0xFFFF;
        store8(sp, value >>> 8);
        sp = (sp - 1) & 0xFFFF;
        store8(sp, value);
    }

    private int pop16() {
        final int lo = load8(sp);
        sp = (sp + 1) & 0xFFFF;
        final int hi = load8(sp);
        sp = (sp + 1) & 0xFFFF;
        return (hi << 8) | lo;
    }

    private int ioRead(final int port) {
        try {
            return (int) ioMap.load(port & 0xFFFF, Sizes.SIZE_8_LOG2) & 0xFF;
        } catch (final MemoryAccessException e) {
            return 0xFF;
        }
    }

    private void ioWrite(final int port, final int value) {
        try {
            ioMap.store(port & 0xFFFF, value & 0xFF, Sizes.SIZE_8_LOG2);
        } catch (final MemoryAccessException ignored) {
        }
    }

    // ------------------------------------------------------------- //
    // Register pairs

    private int getBC() {
        return (r[B] << 8) | r[C];
    }

    private void setBC(final int value) {
        r[B] = (value >>> 8) & 0xFF;
        r[C] = value & 0xFF;
    }

    private int getDE() {
        return (r[D] << 8) | r[E];
    }

    private void setDE(final int value) {
        r[D] = (value >>> 8) & 0xFF;
        r[E] = value & 0xFF;
    }

    private int getHL() {
        return (r[H] << 8) | r[L];
    }

    private void setHL(final int value) {
        r[H] = (value >>> 8) & 0xFF;
        r[L] = value & 0xFF;
    }

    private int getReg16(final int rp) {
        return switch (rp) {
            case 0 -> getBC();
            case 1 -> getDE();
            case 2 -> getHL();
            default -> sp;
        };
    }

    private void setReg16(final int rp, final int value) {
        switch (rp) {
            case 0 -> setBC(value);
            case 1 -> setDE(value);
            case 2 -> setHL(value);
            default -> sp = value & 0xFFFF;
        }
    }

    private int readXY8(final int xy, final int idx) {
        return switch (idx) {
            case H -> ixiy[xy] >>> 8;
            case L -> ixiy[xy] & 0xFF;
            default -> r[idx];
        };
    }

    private void writeXY8(final int xy, final int idx, final int value) {
        switch (idx) {
            case H -> ixiy[xy] = ((value & 0xFF) << 8) | (ixiy[xy] & 0xFF);
            case L -> ixiy[xy] = (ixiy[xy] & 0xFF00) | (value & 0xFF);
            default -> r[idx] = value & 0xFF;
        }
    }

    // ------------------------------------------------------------- //
    // Flags

    private static int szxy(final int value8) {
        return (value8 & (FLAG_S | FLAG_Y | FLAG_X)) | (value8 == 0 ? FLAG_Z : 0);
    }

    private static int parity(final int value8) {
        return (Integer.bitCount(value8) & 1) == 0 ? FLAG_P : 0;
    }

    private boolean condition(final int cc) {
        return switch (cc) {
            case 0 -> (f & FLAG_Z) == 0;
            case 1 -> (f & FLAG_Z) != 0;
            case 2 -> (f & FLAG_C) == 0;
            case 3 -> (f & FLAG_C) != 0;
            case 4 -> (f & FLAG_P) == 0;
            case 5 -> (f & FLAG_P) != 0;
            case 6 -> (f & FLAG_S) == 0;
            default -> (f & FLAG_S) != 0;
        };
    }

    // ------------------------------------------------------------- //
    // ALU

    private void aluOp(final int op, final int value) {
        switch (op) {
            case 0 -> add8(value, 0);
            case 1 -> add8(value, f & FLAG_C);
            case 2 -> sub8(value, 0);
            case 3 -> sub8(value, f & FLAG_C);
            case 4 -> {
                r[A] = r[A] & value;
                f = szxy(r[A]) | parity(r[A]) | FLAG_H;
            }
            case 5 -> {
                r[A] = r[A] ^ value;
                f = szxy(r[A]) | parity(r[A]);
            }
            case 6 -> {
                r[A] = r[A] | value;
                f = szxy(r[A]) | parity(r[A]);
            }
            default -> cp8(value);
        }
    }

    private void add8(final int value, final int carry) {
        final int a = r[A];
        final int result = a + value + carry;
        f = szxy(result & 0xFF)
                | ((a ^ value ^ result) & FLAG_H)
                | ((((~(a ^ value)) & (a ^ result) & 0x80) != 0) ? FLAG_P : 0)
                | ((result >>> 8) & FLAG_C);
        r[A] = result & 0xFF;
    }

    private void sub8(final int value, final int carry) {
        final int a = r[A];
        final int result = a - value - carry;
        f = szxy(result & 0xFF)
                | ((a ^ value ^ result) & FLAG_H)
                | (((a ^ value) & (a ^ result) & 0x80) != 0 ? FLAG_P : 0)
                | FLAG_N
                | ((result >>> 8) & FLAG_C);
        r[A] = result & 0xFF;
    }

    private void cp8(final int value) {
        final int a = r[A];
        final int result = a - value;
        // X/Y come from the operand, not the result.
        f = ((result & 0xFF) == 0 ? FLAG_Z : 0)
                | (result & FLAG_S)
                | (value & (FLAG_Y | FLAG_X))
                | ((a ^ value ^ result) & FLAG_H)
                | (((a ^ value) & (a ^ result) & 0x80) != 0 ? FLAG_P : 0)
                | FLAG_N
                | ((result >>> 8) & FLAG_C);
    }

    private int inc8(final int value) {
        final int result = (value + 1) & 0xFF;
        f = (f & FLAG_C)
                | szxy(result)
                | ((result & 0x0F) == 0 ? FLAG_H : 0)
                | (result == 0x80 ? FLAG_P : 0);
        return result;
    }

    private int dec8(final int value) {
        final int result = (value - 1) & 0xFF;
        f = (f & FLAG_C)
                | szxy(result)
                | ((value & 0x0F) == 0 ? FLAG_H : 0)
                | (result == 0x7F ? FLAG_P : 0)
                | FLAG_N;
        return result;
    }

    private int addHL16(final int lhs, final int value) {
        final int result = lhs + value;
        memptr = (lhs + 1) & 0xFFFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P))
                | (((lhs ^ value ^ result) >>> 8) & FLAG_H)
                | ((result >>> 8) & (FLAG_Y | FLAG_X))
                | ((result >>> 16) & FLAG_C);
        return result & 0xFFFF;
    }

    private void adcHL16(final int value) {
        final int hl = getHL();
        final int carry = f & FLAG_C;
        final int result = hl + value + carry;
        memptr = (hl + 1) & 0xFFFF;
        f = ((result & 0xFFFF) == 0 ? FLAG_Z : 0)
                | ((result >>> 8) & (FLAG_S | FLAG_Y | FLAG_X))
                | (((hl ^ value ^ result) >>> 8) & FLAG_H)
                | ((((~(hl ^ value)) & (hl ^ result) & 0x8000) != 0) ? FLAG_P : 0)
                | ((result >>> 16) & FLAG_C);
        setHL(result & 0xFFFF);
    }

    private void sbcHL16(final int value) {
        final int hl = getHL();
        final int carry = f & FLAG_C;
        final int result = hl - value - carry;
        memptr = (hl + 1) & 0xFFFF;
        f = ((result & 0xFFFF) == 0 ? FLAG_Z : 0)
                | ((result >>> 8) & (FLAG_S | FLAG_Y | FLAG_X))
                | (((hl ^ value ^ result) >>> 8) & FLAG_H)
                | ((((hl ^ value) & (hl ^ result) & 0x8000) != 0) ? FLAG_P : 0)
                | FLAG_N
                | ((result >>> 16) & FLAG_C);
        setHL(result & 0xFFFF);
    }

    private int rot8(final int op, final int value) {
        final int result;
        final int carry;
        switch (op) {
            case 0 -> { // RLC
                carry = value >>> 7;
                result = ((value << 1) | carry) & 0xFF;
            }
            case 1 -> { // RRC
                carry = value & 1;
                result = ((value >>> 1) | (carry << 7)) & 0xFF;
            }
            case 2 -> { // RL
                carry = value >>> 7;
                result = ((value << 1) | (f & FLAG_C)) & 0xFF;
            }
            case 3 -> { // RR
                carry = value & 1;
                result = ((value >>> 1) | ((f & FLAG_C) << 7)) & 0xFF;
            }
            case 4 -> { // SLA
                carry = value >>> 7;
                result = (value << 1) & 0xFF;
            }
            case 5 -> { // SRA
                carry = value & 1;
                result = ((value >>> 1) | (value & 0x80)) & 0xFF;
            }
            case 6 -> { // SLL (undocumented; shifts in a 1)
                carry = value >>> 7;
                result = ((value << 1) | 1) & 0xFF;
            }
            default -> { // SRL
                carry = value & 1;
                result = value >>> 1;
            }
        }
        f = szxy(result) | parity(result) | carry;
        return result;
    }

    private void bitTest(final int b, final int value, final int xySource) {
        final int masked = value & (1 << b);
        f = (f & FLAG_C)
                | FLAG_H
                | (masked == 0 ? (FLAG_Z | FLAG_P) : 0)
                | (masked & FLAG_S)
                | (xySource & (FLAG_Y | FLAG_X));
    }

    // ------------------------------------------------------------- //
    // Main page

    @Instruction("NOP")
    void nop() {
        cycles += 4;
    }

    @Instruction("LD_RP_NN")
    void ld_rp_nn(@Field("rp") final int rp, @Field("nn") final int nn) {
        setReg16(rp, nn);
        cycles += 10;
    }

    @Instruction("LD_BCDE_A")
    void ld_bcde_a(@Field("q") final int q) {
        final int address = q == 0 ? getBC() : getDE();
        store8(address, r[A]);
        memptr = (r[A] << 8) | ((address + 1) & 0xFF);
        cycles += 7;
    }

    @Instruction("LD_A_BCDE")
    void ld_a_bcde(@Field("q") final int q) {
        final int address = q == 0 ? getBC() : getDE();
        r[A] = load8(address);
        memptr = (address + 1) & 0xFFFF;
        cycles += 7;
    }

    @Instruction("INC_RP")
    void inc_rp(@Field("rp") final int rp) {
        setReg16(rp, (getReg16(rp) + 1) & 0xFFFF);
        cycles += 6;
    }

    @Instruction("DEC_RP")
    void dec_rp(@Field("rp") final int rp) {
        setReg16(rp, (getReg16(rp) - 1) & 0xFFFF);
        cycles += 6;
    }

    @Instruction("INC_R")
    void inc_r(@Field("y") final int y) {
        r[y] = inc8(r[y]);
        cycles += 4;
    }

    @Instruction("DEC_R")
    void dec_r(@Field("y") final int y) {
        r[y] = dec8(r[y]);
        cycles += 4;
    }

    @Instruction("INC_HLI")
    void inc_hli() {
        final int address = getHL();
        store8(address, inc8(load8(address)));
        cycles += 11;
    }

    @Instruction("DEC_HLI")
    void dec_hli() {
        final int address = getHL();
        store8(address, dec8(load8(address)));
        cycles += 11;
    }

    @Instruction("LD_R_N")
    void ld_r_n(@Field("y") final int y, @Field("n") final int n) {
        r[y] = n;
        cycles += 7;
    }

    @Instruction("LD_HLI_N")
    void ld_hli_n(@Field("n") final int n) {
        store8(getHL(), n);
        cycles += 10;
    }

    @Instruction("RLCA")
    void rlca() {
        final int carry = r[A] >>> 7;
        r[A] = ((r[A] << 1) | carry) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | (r[A] & (FLAG_Y | FLAG_X)) | carry;
        cycles += 4;
    }

    @Instruction("RRCA")
    void rrca() {
        final int carry = r[A] & 1;
        r[A] = ((r[A] >>> 1) | (carry << 7)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | (r[A] & (FLAG_Y | FLAG_X)) | carry;
        cycles += 4;
    }

    @Instruction("RLA")
    void rla() {
        final int carry = r[A] >>> 7;
        r[A] = ((r[A] << 1) | (f & FLAG_C)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | (r[A] & (FLAG_Y | FLAG_X)) | carry;
        cycles += 4;
    }

    @Instruction("RRA")
    void rra() {
        final int carry = r[A] & 1;
        r[A] = ((r[A] >>> 1) | ((f & FLAG_C) << 7)) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | (r[A] & (FLAG_Y | FLAG_X)) | carry;
        cycles += 4;
    }

    @Instruction("EX_AF_AF2")
    void ex_af_af2() {
        final int a = r[A];
        r[A] = r2[A];
        r2[A] = a;
        final int flags = f;
        f = f2;
        f2 = flags;
        cycles += 4;
    }

    @Instruction("ADD_HL_RP")
    void add_hl_rp(@Field("rp") final int rp) {
        setHL(addHL16(getHL(), getReg16(rp)));
        cycles += 11;
    }

    @Branch
    @Instruction("DJNZ")
    boolean djnz(@Field("d") final int d, @ProgramCounter final long pc, @InstructionSize final int size) {
        r[B] = (r[B] - 1) & 0xFF;
        if (r[B] != 0) {
            final int target = (int) (pc + size + d) & 0xFFFF;
            this.pc = target;
            memptr = target;
            cycles += 13;
            return true;
        }
        cycles += 8;
        return false;
    }

    @Branch
    @Instruction("JR")
    void jr(@Field("d") final int d, @ProgramCounter final long pc, @InstructionSize final int size) {
        final int target = (int) (pc + size + d) & 0xFFFF;
        this.pc = target;
        memptr = target;
        cycles += 12;
    }

    @Branch
    @Instruction("JR_CC")
    boolean jr_cc(@Field("cc") final int cc, @Field("d") final int d, @ProgramCounter final long pc, @InstructionSize final int size) {
        if (condition(cc)) {
            final int target = (int) (pc + size + d) & 0xFFFF;
            this.pc = target;
            memptr = target;
            cycles += 12;
            return true;
        }
        cycles += 7;
        return false;
    }

    @Instruction("LD_INN_HL")
    void ld_inn_hl(@Field("nn") final int nn) {
        store16(nn, getHL());
        memptr = (nn + 1) & 0xFFFF;
        cycles += 16;
    }

    @Instruction("LD_HL_INN")
    void ld_hl_inn(@Field("nn") final int nn) {
        setHL(load16(nn));
        memptr = (nn + 1) & 0xFFFF;
        cycles += 16;
    }

    @Instruction("LD_INN_A")
    void ld_inn_a(@Field("nn") final int nn) {
        store8(nn, r[A]);
        memptr = (r[A] << 8) | ((nn + 1) & 0xFF);
        cycles += 13;
    }

    @Instruction("LD_A_INN")
    void ld_a_inn(@Field("nn") final int nn) {
        r[A] = load8(nn);
        memptr = (nn + 1) & 0xFFFF;
        cycles += 13;
    }

    @Instruction("DAA")
    void daa() {
        final int a = r[A];
        int adjust = 0;
        int carry = f & FLAG_C;
        if ((f & FLAG_H) != 0 || (a & 0x0F) > 9) {
            adjust = 0x06;
        }
        if (carry != 0 || a > 0x99) {
            adjust |= 0x60;
            carry = FLAG_C;
        }
        final int result = ((f & FLAG_N) != 0 ? a - adjust : a + adjust) & 0xFF;
        f = szxy(result) | parity(result) | (f & FLAG_N) | carry | ((a ^ result) & FLAG_H);
        r[A] = result;
        cycles += 4;
    }

    @Instruction("CPL")
    void cpl() {
        r[A] = (~r[A]) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P | FLAG_C)) | FLAG_H | FLAG_N | (r[A] & (FLAG_Y | FLAG_X));
        cycles += 4;
    }

    @Instruction("SCF")
    void scf() {
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | FLAG_C | (r[A] & (FLAG_Y | FLAG_X));
        cycles += 4;
    }

    @Instruction("CCF")
    void ccf() {
        final int carry = f & FLAG_C;
        f = (f & (FLAG_S | FLAG_Z | FLAG_P)) | (carry != 0 ? FLAG_H : FLAG_C) | (r[A] & (FLAG_Y | FLAG_X));
        cycles += 4;
    }

    @Instruction("LD_R_R")
    void ld_r_r(@Field("y") final int y, @Field("z") final int z) {
        r[y] = r[z];
        cycles += 4;
    }

    @Instruction("LD_R_HLI")
    void ld_r_hli(@Field("y") final int y) {
        r[y] = load8(getHL());
        cycles += 7;
    }

    @Instruction("LD_HLI_R")
    void ld_hli_r(@Field("z") final int z) {
        store8(getHL(), r[z]);
        cycles += 7;
    }

    @Instruction("HALT")
    boolean halt() {
        halted = true;
        cycles += 4;
        return true;
    }

    @Instruction("ALU_A_R")
    void alu_a_r(@Field("alu") final int alu, @Field("z") final int z) {
        aluOp(alu, r[z]);
        cycles += 4;
    }

    @Instruction("ALU_A_HLI")
    void alu_a_hli(@Field("alu") final int alu) {
        aluOp(alu, load8(getHL()));
        cycles += 7;
    }

    @Instruction("ALU_A_N")
    void alu_a_n(@Field("alu") final int alu, @Field("n") final int n) {
        aluOp(alu, n);
        cycles += 7;
    }

    @Branch
    @Instruction("RET_CC")
    boolean ret_cc(@Field("cc") final int cc) {
        if (condition(cc)) {
            final int target = pop16();
            pc = target;
            memptr = target;
            cycles += 11;
            return true;
        }
        cycles += 5;
        return false;
    }

    @Branch
    @Instruction("RET")
    void ret() {
        final int target = pop16();
        pc = target;
        memptr = target;
        cycles += 10;
    }

    @Instruction("POP_RP2")
    void pop_rp2(@Field("rp") final int rp) {
        final int value = pop16();
        if (rp == 3) {
            r[A] = value >>> 8;
            f = value & 0xFF;
        } else {
            setReg16(rp, value);
        }
        cycles += 10;
    }

    @Instruction("PUSH_RP2")
    void push_rp2(@Field("rp") final int rp) {
        push16(rp == 3 ? ((r[A] << 8) | f) : getReg16(rp));
        cycles += 11;
    }

    @Branch
    @Instruction("JP_CC")
    boolean jp_cc(@Field("cc") final int cc, @Field("nn") final int nn) {
        memptr = nn;
        cycles += 10;
        if (condition(cc)) {
            pc = nn;
            return true;
        }
        return false;
    }

    @Branch
    @Instruction("JP_NN")
    void jp_nn(@Field("nn") final int nn) {
        pc = nn;
        memptr = nn;
        cycles += 10;
    }

    @Branch
    @Instruction("CALL_CC")
    boolean call_cc(@Field("cc") final int cc, @Field("nn") final int nn, @ProgramCounter final long pc, @InstructionSize final int size) {
        memptr = nn;
        if (condition(cc)) {
            push16((int) (pc + size));
            this.pc = nn;
            cycles += 17;
            return true;
        }
        cycles += 10;
        return false;
    }

    @Branch
    @Instruction("CALL_NN")
    void call_nn(@Field("nn") final int nn, @ProgramCounter final long pc, @InstructionSize final int size) {
        push16((int) (pc + size));
        this.pc = nn;
        memptr = nn;
        cycles += 17;
    }

    @Branch
    @Instruction("RST")
    void rst(@Field("t") final int t, @ProgramCounter final long pc, @InstructionSize final int size) {
        push16((int) (pc + size));
        this.pc = t << 3;
        memptr = t << 3;
        cycles += 11;
    }

    @Instruction("OUT_N_A")
    void out_n_a(@Field("n") final int n) {
        ioWrite((r[A] << 8) | n, r[A]);
        memptr = (r[A] << 8) | ((n + 1) & 0xFF);
        cycles += 11;
    }

    @Instruction("IN_A_N")
    void in_a_n(@Field("n") final int n) {
        final int port = (r[A] << 8) | n;
        r[A] = ioRead(port);
        memptr = (port + 1) & 0xFFFF;
        cycles += 11;
    }

    @Instruction("EXX")
    void exx() {
        for (int j = B; j <= L; j++) {
            final int value = r[j];
            r[j] = r2[j];
            r2[j] = value;
        }
        cycles += 4;
    }

    @Instruction("EX_DE_HL")
    void ex_de_hl() {
        final int de = getDE();
        setDE(getHL());
        setHL(de);
        cycles += 4;
    }

    @Instruction("EX_SP_HL")
    void ex_sp_hl() {
        final int value = load16(sp);
        store16(sp, getHL());
        setHL(value);
        memptr = value;
        cycles += 19;
    }

    @Branch
    @Instruction("JP_HL")
    void jp_hl() {
        pc = getHL();
        cycles += 4;
    }

    @Instruction("LD_SP_HL")
    void ld_sp_hl() {
        sp = getHL();
        cycles += 6;
    }

    @Instruction("DI")
    void di() {
        iff1 = false;
        iff2 = false;
        cycles += 4;
    }

    @Instruction("EI")
    boolean ei() {
        iff1 = true;
        iff2 = true;
        eiDelay = true;
        cycles += 4;
        return true;
    }

    // ------------------------------------------------------------- //
    // CB page

    @Instruction("ROT_R")
    void rot_r(@Field("op") final int op, @Field("r") final int reg) {
        r[reg] = rot8(op, r[reg]);
        cycles += 8;
    }

    @Instruction("ROT_HLI")
    void rot_hli(@Field("op") final int op) {
        final int address = getHL();
        store8(address, rot8(op, load8(address)));
        cycles += 15;
    }

    @Instruction("BIT_R")
    void bit_r(@Field("b") final int b, @Field("r") final int reg) {
        bitTest(b, r[reg], r[reg]);
        cycles += 8;
    }

    @Instruction("BIT_HLI")
    void bit_hli(@Field("b") final int b) {
        bitTest(b, load8(getHL()), memptr >>> 8);
        cycles += 12;
    }

    @Instruction("RES_R")
    void res_r(@Field("b") final int b, @Field("r") final int reg) {
        r[reg] &= ~(1 << b);
        cycles += 8;
    }

    @Instruction("RES_HLI")
    void res_hli(@Field("b") final int b) {
        final int address = getHL();
        store8(address, load8(address) & ~(1 << b));
        cycles += 15;
    }

    @Instruction("SET_R")
    void set_r(@Field("b") final int b, @Field("r") final int reg) {
        r[reg] |= 1 << b;
        cycles += 8;
    }

    @Instruction("SET_HLI")
    void set_hli(@Field("b") final int b) {
        final int address = getHL();
        store8(address, load8(address) | (1 << b));
        cycles += 15;
    }

    // ------------------------------------------------------------- //
    // ED page

    @Instruction("IN_R_C")
    void in_r_c(@Field("y") final int y) {
        final int bc = getBC();
        final int value = ioRead(bc);
        if (y != 6) { // Undocumented IN (C): flags only.
            r[y] = value;
        }
        f = (f & FLAG_C) | szxy(value) | parity(value);
        memptr = (bc + 1) & 0xFFFF;
        cycles += 12;
    }

    @Instruction("OUT_C_R")
    void out_c_r(@Field("y") final int y) {
        final int bc = getBC();
        ioWrite(bc, y == 6 ? 0 : r[y]); // Undocumented OUT (C),0.
        memptr = (bc + 1) & 0xFFFF;
        cycles += 12;
    }

    @Instruction("SBC_HL_RP")
    void sbc_hl_rp(@Field("rp") final int rp) {
        sbcHL16(getReg16(rp));
        cycles += 15;
    }

    @Instruction("ADC_HL_RP")
    void adc_hl_rp(@Field("rp") final int rp) {
        adcHL16(getReg16(rp));
        cycles += 15;
    }

    @Instruction("LD_INN_RP")
    void ld_inn_rp(@Field("rp") final int rp, @Field("nn") final int nn) {
        store16(nn, getReg16(rp));
        memptr = (nn + 1) & 0xFFFF;
        cycles += 20;
    }

    @Instruction("LD_RP_INN")
    void ld_rp_inn(@Field("rp") final int rp, @Field("nn") final int nn) {
        setReg16(rp, load16(nn));
        memptr = (nn + 1) & 0xFFFF;
        cycles += 20;
    }

    @Instruction("NEG")
    void neg() {
        final int value = r[A];
        r[A] = 0;
        sub8(value, 0);
        cycles += 8;
    }

    @Branch
    @Instruction("RETI_RETN")
    void reti_retn() {
        iff1 = iff2;
        final int target = pop16();
        pc = target;
        memptr = target;
        cycles += 14;
    }

    @Instruction("IM_Y")
    void im_y(@Field("y") final int y) {
        im = switch (y & 3) {
            case 2 -> 1;
            case 3 -> 2;
            default -> 0;
        };
        cycles += 8;
    }

    @Instruction("LD_I_A")
    void ld_i_a() {
        i = r[A];
        cycles += 9;
    }

    @Instruction("LD_R_A")
    void ld_r_a() {
        rr = r[A];
        cycles += 9;
    }

    @Instruction("LD_A_I")
    void ld_a_i() {
        r[A] = i;
        f = (f & FLAG_C) | szxy(r[A]) | (iff2 ? FLAG_P : 0);
        cycles += 9;
    }

    @Instruction("LD_A_R")
    void ld_a_r() {
        r[A] = rr;
        f = (f & FLAG_C) | szxy(r[A]) | (iff2 ? FLAG_P : 0);
        cycles += 9;
    }

    @Instruction("RRD")
    void rrd() {
        final int address = getHL();
        final int value = load8(address);
        store8(address, ((r[A] << 4) | (value >>> 4)) & 0xFF);
        r[A] = (r[A] & 0xF0) | (value & 0x0F);
        f = (f & FLAG_C) | szxy(r[A]) | parity(r[A]);
        memptr = (address + 1) & 0xFFFF;
        cycles += 18;
    }

    @Instruction("RLD")
    void rld() {
        final int address = getHL();
        final int value = load8(address);
        store8(address, ((value << 4) | (r[A] & 0x0F)) & 0xFF);
        r[A] = (r[A] & 0xF0) | (value >>> 4);
        f = (f & FLAG_C) | szxy(r[A]) | parity(r[A]);
        memptr = (address + 1) & 0xFFFF;
        cycles += 18;
    }

    @Branch
    @Instruction("LDX")
    boolean ldx(@Field("rep") final int rep, @Field("dir") final int dir, @ProgramCounter final long pc) {
        final int step = dir == 0 ? 1 : -1;
        final int hl = getHL();
        final int de = getDE();
        final int value = load8(hl);
        store8(de, value);
        setHL((hl + step) & 0xFFFF);
        setDE((de + step) & 0xFFFF);
        final int bc = (getBC() - 1) & 0xFFFF;
        setBC(bc);
        final int undoc = (r[A] + value) & 0xFF;
        f = (f & (FLAG_S | FLAG_Z | FLAG_C))
                | (bc != 0 ? FLAG_P : 0)
                | ((undoc & 0x02) != 0 ? FLAG_Y : 0)
                | (undoc & FLAG_X);
        cycles += 16;
        if (rep != 0 && bc != 0) {
            f = (f & ~(FLAG_Y | FLAG_X)) | ((int) (pc >>> 8) & (FLAG_Y | FLAG_X));
            this.pc = pc;
            memptr = (int) (pc + 1) & 0xFFFF;
            cycles += 5;
            return true;
        }
        return false;
    }

    @Branch
    @Instruction("CPX")
    boolean cpx(@Field("rep") final int rep, @Field("dir") final int dir, @ProgramCounter final long pc) {
        final int step = dir == 0 ? 1 : -1;
        final int hl = getHL();
        final int value = load8(hl);
        final int result = (r[A] - value) & 0xFF;
        final int halfBorrow = (r[A] ^ value ^ result) & FLAG_H;
        setHL((hl + step) & 0xFFFF);
        final int bc = (getBC() - 1) & 0xFFFF;
        setBC(bc);
        final int undoc = (result - (halfBorrow != 0 ? 1 : 0)) & 0xFF;
        f = (f & FLAG_C)
                | (result == 0 ? FLAG_Z : 0)
                | (result & FLAG_S)
                | halfBorrow
                | (bc != 0 ? FLAG_P : 0)
                | FLAG_N
                | ((undoc & 0x02) != 0 ? FLAG_Y : 0)
                | (undoc & FLAG_X);
        memptr = (memptr + step) & 0xFFFF;
        cycles += 16;
        if (rep != 0 && bc != 0 && result != 0) {
            f = (f & ~(FLAG_Y | FLAG_X)) | ((int) (pc >>> 8) & (FLAG_Y | FLAG_X));
            this.pc = pc;
            memptr = (int) (pc + 1) & 0xFFFF;
            cycles += 5;
            return true;
        }
        return false;
    }

    @Branch
    @Instruction("INX")
    boolean inx(@Field("rep") final int rep, @Field("dir") final int dir, @ProgramCounter final long pc) {
        final int step = dir == 0 ? 1 : -1;
        final int bc = getBC();
        final int value = ioRead(bc);
        final int hl = getHL();
        store8(hl, value);
        setHL((hl + step) & 0xFFFF);
        final int b = (r[B] - 1) & 0xFF;
        r[B] = b;
        memptr = (bc + step) & 0xFFFF;
        final int k = value + ((r[C] + step) & 0xFF);
        f = szxy(b)
                | ((value & 0x80) != 0 ? FLAG_N : 0)
                | (k > 0xFF ? (FLAG_H | FLAG_C) : 0)
                | parity((k & 7) ^ b);
        cycles += 16;
        if (rep != 0 && b != 0) {
            applyRepeatInOutFlags(value, b, pc);
            this.pc = pc;
            cycles += 5;
            return true;
        }
        return false;
    }

    @Branch
    @Instruction("OUTX")
    boolean outx(@Field("rep") final int rep, @Field("dir") final int dir, @ProgramCounter final long pc) {
        final int step = dir == 0 ? 1 : -1;
        final int hl = getHL();
        final int value = load8(hl);
        final int b = (r[B] - 1) & 0xFF;
        r[B] = b;
        ioWrite(getBC(), value);
        setHL((hl + step) & 0xFFFF);
        memptr = (getBC() + step) & 0xFFFF;
        final int k = value + r[L];
        f = szxy(b)
                | ((value & 0x80) != 0 ? FLAG_N : 0)
                | (k > 0xFF ? (FLAG_H | FLAG_C) : 0)
                | parity((k & 7) ^ b);
        cycles += 16;
        if (rep != 0 && b != 0) {
            applyRepeatInOutFlags(value, b, pc);
            this.pc = pc;
            cycles += 5;
            return true;
        }
        return false;
    }

    /**
     * Flag adjustments of INxR/OTxR repeat iterations, after David Banks' "The Undocumented Z80
     * Flags" research: X/Y come from the high byte of the instruction address, and H/P are
     * derived from the on-the-fly decremented B.
     */
    private void applyRepeatInOutFlags(final int value, final int b, final long pc) {
        f = (f & ~(FLAG_Y | FLAG_X)) | ((int) (pc >>> 8) & (FLAG_Y | FLAG_X));
        if ((f & FLAG_C) != 0) {
            if ((value & 0x80) != 0) {
                f ^= parity((b - 1) & 7) ^ FLAG_P;
                f = (f & ~FLAG_H) | ((b & 0x0F) == 0x00 ? FLAG_H : 0);
            } else {
                f ^= parity((b + 1) & 7) ^ FLAG_P;
                f = (f & ~FLAG_H) | ((b & 0x0F) == 0x0F ? FLAG_H : 0);
            }
        } else {
            f ^= parity(b & 7) ^ FLAG_P;
        }
        memptr = (int) (pc + 1) & 0xFFFF;
    }

    @Instruction("ED_NONI")
    void ed_noni() {
        cycles += 8;
    }

    // ------------------------------------------------------------- //
    // DD/FD page

    @Branch
    @Instruction("XY_ED")
    void xy_ed(@ProgramCounter final long pc) {
        prefixChain(pc);
    }

    @Branch
    @Instruction("XY_CHAIN")
    void xy_chain(@ProgramCounter final long pc) {
        prefixChain(pc);
    }

    /**
     * The prefix executes as a stand-alone 4T no-op and decoding restarts at the following byte.
     * bumpR counted this fetch as a prefixed instruction (2 M1 cycles) and the re-decode will
     * count the follower again, but only the prefix's own M1 has happened, so take one back.
     */
    private void prefixChain(final long pc) {
        rr = (rr & 0x80) | ((rr - 1) & 0x7F);
        this.pc = (pc + 1) & 0xFFFF;
        cycles += 4;
    }

    @Instruction("XY_HALT")
    boolean xy_halt() {
        halted = true;
        cycles += 8;
        return true;
    }

    @Instruction("ADD_XY_RP")
    void add_xy_rp(@Field("xy") final int xy, @Field("rp") final int rp) {
        final int value = rp == 2 ? ixiy[xy] : getReg16(rp);
        ixiy[xy] = addHL16(ixiy[xy], value);
        cycles += 15;
    }

    @Instruction("LD_XY_NN")
    void ld_xy_nn(@Field("xy") final int xy, @Field("nn") final int nn) {
        ixiy[xy] = nn;
        cycles += 14;
    }

    @Instruction("LD_INN_XY")
    void ld_inn_xy(@Field("xy") final int xy, @Field("nn") final int nn) {
        store16(nn, ixiy[xy]);
        memptr = (nn + 1) & 0xFFFF;
        cycles += 20;
    }

    @Instruction("LD_XY_INN")
    void ld_xy_inn(@Field("xy") final int xy, @Field("nn") final int nn) {
        ixiy[xy] = load16(nn);
        memptr = (nn + 1) & 0xFFFF;
        cycles += 20;
    }

    @Instruction("INC_XY")
    void inc_xy(@Field("xy") final int xy) {
        ixiy[xy] = (ixiy[xy] + 1) & 0xFFFF;
        cycles += 10;
    }

    @Instruction("DEC_XY")
    void dec_xy(@Field("xy") final int xy) {
        ixiy[xy] = (ixiy[xy] - 1) & 0xFFFF;
        cycles += 10;
    }

    @Instruction("INC_XY8")
    void inc_xy8(@Field("xy") final int xy, @Field("hl") final int hl) {
        final int idx = H + hl;
        writeXY8(xy, idx, inc8(readXY8(xy, idx)));
        cycles += 8;
    }

    @Instruction("DEC_XY8")
    void dec_xy8(@Field("xy") final int xy, @Field("hl") final int hl) {
        final int idx = H + hl;
        writeXY8(xy, idx, dec8(readXY8(xy, idx)));
        cycles += 8;
    }

    @Instruction("LD_XY8_N")
    void ld_xy8_n(@Field("xy") final int xy, @Field("hl") final int hl, @Field("n") final int n) {
        writeXY8(xy, H + hl, n);
        cycles += 11;
    }

    @Instruction("INC_XYD")
    void inc_xyd(@Field("xy") final int xy, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        store8(address, inc8(load8(address)));
        cycles += 23;
    }

    @Instruction("DEC_XYD")
    void dec_xyd(@Field("xy") final int xy, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        store8(address, dec8(load8(address)));
        cycles += 23;
    }

    @Instruction("LD_XYD_N")
    void ld_xyd_n(@Field("xy") final int xy, @Field("d") final int d, @Field("n") final int n) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        store8(address, n);
        cycles += 19;
    }

    @Instruction("LD_R_R_XY")
    void ld_r_r_xy(@Field("xy") final int xy, @Field("y") final int y, @Field("z") final int z) {
        writeXY8(xy, y, readXY8(xy, z));
        cycles += 8;
    }

    @Instruction("LD_R_XYD")
    void ld_r_xyd(@Field("xy") final int xy, @Field("y") final int y, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        r[y] = load8(address);
        cycles += 19;
    }

    @Instruction("LD_XYD_R")
    void ld_xyd_r(@Field("xy") final int xy, @Field("z") final int z, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        store8(address, r[z]);
        cycles += 19;
    }

    @Instruction("ALU_A_XY8")
    void alu_a_xy8(@Field("xy") final int xy, @Field("alu") final int alu, @Field("z") final int z) {
        aluOp(alu, readXY8(xy, z));
        cycles += 8;
    }

    @Instruction("ALU_A_XYD")
    void alu_a_xyd(@Field("xy") final int xy, @Field("alu") final int alu, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        aluOp(alu, load8(address));
        cycles += 19;
    }

    @Instruction("POP_XY")
    void pop_xy(@Field("xy") final int xy) {
        ixiy[xy] = pop16();
        cycles += 14;
    }

    @Instruction("PUSH_XY")
    void push_xy(@Field("xy") final int xy) {
        push16(ixiy[xy]);
        cycles += 15;
    }

    @Instruction("EX_SP_XY")
    void ex_sp_xy(@Field("xy") final int xy) {
        final int value = load16(sp);
        store16(sp, ixiy[xy]);
        ixiy[xy] = value;
        memptr = value;
        cycles += 23;
    }

    @Branch
    @Instruction("JP_XY")
    void jp_xy(@Field("xy") final int xy) {
        pc = ixiy[xy];
        cycles += 8;
    }

    @Instruction("LD_SP_XY")
    void ld_sp_xy(@Field("xy") final int xy) {
        sp = ixiy[xy];
        cycles += 10;
    }

    // ------------------------------------------------------------- //
    // DDCB/FDCB page

    @Instruction("QROT")
    void qrot(@Field("xy") final int xy, @Field("op") final int op, @Field("r") final int reg, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        final int result = rot8(op, load8(address));
        store8(address, result);
        if (reg != 6) { // Undocumented: result is also copied to the register.
            r[reg] = result;
        }
        cycles += 23;
    }

    @Instruction("QBIT")
    void qbit(@Field("xy") final int xy, @Field("b") final int b, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        bitTest(b, load8(address), address >>> 8);
        cycles += 20;
    }

    @Instruction("QRES")
    void qres(@Field("xy") final int xy, @Field("b") final int b, @Field("r") final int reg, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        final int result = load8(address) & ~(1 << b);
        store8(address, result);
        if (reg != 6) {
            r[reg] = result;
        }
        cycles += 23;
    }

    @Instruction("QSET")
    void qset(@Field("xy") final int xy, @Field("b") final int b, @Field("r") final int reg, @Field("d") final int d) {
        final int address = (ixiy[xy] + d) & 0xFFFF;
        memptr = address;
        final int result = load8(address) | (1 << b);
        store8(address, result);
        if (reg != 6) {
            r[reg] = result;
        }
        cycles += 23;
    }

    // ------------------------------------------------------------- //
    // Debugging

    private static final int REG_AF = 0;
    private static final int REG_BC = 1;
    private static final int REG_DE = 2;
    private static final int REG_HL = 3;
    private static final int REG_SP = 4;
    private static final int REG_PC = 5;
    private static final int REG_IX = 6;
    private static final int REG_IY = 7;
    private static final int REG_AF2 = 8;
    private static final int REG_BC2 = 9;
    private static final int REG_DE2 = 10;
    private static final int REG_HL2 = 11;
    private static final int REG_IR = 12;
    private static final int REG_IM = 13;
    private static final int REG_IFF1 = 14;
    private static final int REG_IFF2 = 15;

    private static final class TargetDescription {
        private static final String RESOURCE_PATH = "/gdb/target-z80.xml";

        @Nullable
        static final byte[] VALUE = load();

        @Nullable
        private static byte[] load() {
            try (final InputStream stream = Z80CPUBase.class.getResourceAsStream(RESOURCE_PATH)) {
                return stream != null ? stream.readAllBytes() : null;
            } catch (final IOException e) {
                LOGGER.warn("Failed loading GDB target description", e);
                return null; // Debugger has to fall back to the general registers.
            }
        }
    }

    final class DebugInterface implements CPUDebugInterface {
        private final Collection<LongConsumer> breakpointListeners = new ArrayList<>();
        private final LongSet breakpoints = new LongOpenHashSet();

        @Override
        public long getProgramCounter() {
            return pc & 0xFFFF;
        }

        @Override
        public void setProgramCounter(final long value) {
            pc = value & 0xFFFF;
        }

        @Override
        public void step() {
            if (nmiRequested) {
                acceptNMI();
            } else if (irqRequested && iff1 && !eiDelay) {
                acceptIRQ();
            }

            if (halted) {
                return;
            }

            eiDelay = false;
            interpretTrace(true, null);
            pc &= 0xFFFF;
        }

        @Override
        public int getGeneralRegisterCount() {
            return REG_IFF2 + 1;
        }

        @Nullable
        @Override
        public byte[] getTargetDescription() {
            return TargetDescription.VALUE;
        }

        @Override
        public int getRegisterSize(final int id) {
            if (id >= REG_AF && id <= REG_IR) return 2;
            if (id >= REG_IM && id <= REG_IFF2) return 1;
            return 0;
        }

        @Override
        public long getRegister(final int id) {
            return switch (id) {
                case REG_AF -> (r[A] << 8) | f;
                case REG_BC -> getBC();
                case REG_DE -> getDE();
                case REG_HL -> getHL();
                case REG_SP -> sp;
                case REG_PC -> pc & 0xFFFF;
                case REG_IX -> ixiy[0];
                case REG_IY -> ixiy[1];
                case REG_AF2 -> (r2[A] << 8) | f2;
                case REG_BC2 -> (r2[B] << 8) | r2[C];
                case REG_DE2 -> (r2[D] << 8) | r2[E];
                case REG_HL2 -> (r2[H] << 8) | r2[L];
                case REG_IR -> (i << 8) | rr;
                case REG_IM -> im;
                case REG_IFF1 -> iff1 ? 1 : 0;
                case REG_IFF2 -> iff2 ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public boolean setRegister(final int id, final long value) {
            final int hi = (int) (value >>> 8) & 0xFF;
            final int lo = (int) value & 0xFF;
            switch (id) {
                case REG_AF -> { r[A] = hi; f = lo; }
                case REG_BC -> { r[B] = hi; r[C] = lo; }
                case REG_DE -> { r[D] = hi; r[E] = lo; }
                case REG_HL -> { r[H] = hi; r[L] = lo; }
                case REG_SP -> sp = (int) value & 0xFFFF;
                case REG_PC -> pc = value & 0xFFFF;
                case REG_IX -> ixiy[0] = (int) value & 0xFFFF;
                case REG_IY -> ixiy[1] = (int) value & 0xFFFF;
                case REG_AF2 -> { r2[A] = hi; f2 = lo; }
                case REG_BC2 -> { r2[B] = hi; r2[C] = lo; }
                case REG_DE2 -> { r2[D] = hi; r2[E] = lo; }
                case REG_HL2 -> { r2[H] = hi; r2[L] = lo; }
                case REG_IR -> { i = hi; rr = lo; }
                case REG_IM -> {
                    if (lo > 2) {
                        return false;
                    }
                    im = lo;
                }
                case REG_IFF1 -> iff1 = lo != 0;
                case REG_IFF2 -> iff2 = lo != 0;
                default -> {
                    return false;
                }
            }
            return true;
        }

        @Override
        public byte[] loadDebug(final long address, final int size) {
            final byte[] data = new byte[size];
            for (int offset = 0; offset < size; offset++) {
                try {
                    data[offset] = (byte) memoryMap.load((address + offset) & 0xFFFF, Sizes.SIZE_8_LOG2);
                } catch (final MemoryAccessException e) {
                    return Arrays.copyOf(data, offset); // Partial reads are okay.
                }
            }
            return data;
        }

        @Override
        public int storeDebug(final long address, final byte[] data) {
            for (int offset = 0; offset < data.length; offset++) {
                try {
                    memoryMap.store((address + offset) & 0xFFFF, data[offset], Sizes.SIZE_8_LOG2);
                } catch (final MemoryAccessException e) {
                    return offset;
                }
            }
            return data.length;
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
            breakpoints.add(address & 0xFFFF);
        }

        @Override
        public void removeBreakpoint(final long address) {
            breakpoints.remove(address & 0xFFFF);
        }

        void handleBreakpoint(final long pc) {
            debugStop = true;
            for (final LongConsumer listener : breakpointListeners) {
                listener.accept(pc);
            }
        }
    }
}
