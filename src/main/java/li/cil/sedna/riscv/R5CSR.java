package li.cil.sedna.riscv;

@SuppressWarnings({"unused", "RedundantSuppression"})
public final class R5CSR {
    // Floating-Point Control and Status Registers
    public static final int FFLAGS = 0x001; // Floating-Point Accrued Exceptions.
    public static final int FRM = 0x002; // Floating-Point Dynamic Rounding Mode.
    public static final int FCSR = 0x003; // Floating-Point Control and Status Register (frm + fflags).

    // Supervisor Trap Setup
    public static final int SSTATUS = 0x100; // Supervisor status register.
    public static final int SIE = 0x104; // Supervisor interrupt-enable register.
    public static final int STVEC = 0x105; // Supervisor trap handler base address.
    public static final int SCOUNTEREN = 0x106; // Supervisor counter enable.

    // Supervisor Trap Handling
    public static final int SSCRATCH = 0x140; // Scratch register for supervisor trap handlers.
    public static final int SEPC = 0x141; // Supervisor exception program counter.
    public static final int SCAUSE = 0x142; // Supervisor trap cause.
    public static final int STVAL = 0x143; // Supervisor bad address or instruction.
    public static final int SIP = 0x144; // Supervisor interrupt pending.

    // Supervisor Protection and Translation
    public static final int SATP = 0x180; // Supervisor address translation and protection.

    // Machine Trap Setup
    public static final int MSTATUS = 0x300; // Machine status register.
    public static final int MISA = 0x301; // ISA and extensions.
    public static final int MEDELEG = 0x302; // Machine exception delegation register.
    public static final int MIDELEG = 0x303; // Machine interrupt delegation register.
    public static final int MIE = 0x304; // Machine interrupt-enable register.
    public static final int MTVEC = 0x305; // Machine trap-handler base address.
    public static final int MCOUNTEREN = 0x306; // Machine counter enable.
    public static final int MSTATUSH = 0x310; // Additional machine status register, RV32 only.

    // Machine Trap Handling
    public static final int MSCRATCH = 0x340; // Scratch register for machine trap handlers.
    public static final int MEPC = 0x341; // Machine exception program counter.
    public static final int MCAUSE = 0x342; // Machine trap cause.
    public static final int MTVAL = 0x343; // Machine bad address or instruction.
    public static final int MIP = 0x344; // Machine interrupt pending.

    // Debug/Trace Registers
    public static final int TSELECT = 0x7A0;
    public static final int TDATA1 = 0x7A1;
    public static final int TDATA2 = 0x7A2;
    public static final int TDATA3 = 0x7A3;

    // Machine Counter/Timers
    public static final int MCYCLE = 0xB00; // Machine cycle counter.
    public static final int MINSTRET = 0xB02; // Machine instructions-retired counter.
    public static final int MCYCLEH = 0xB80; // Upper 32 bits of mcycle, RV32 only.
    public static final int MINSTRETH = 0xB82; // Upper 32 bits of minstret, RV32 only.

    // Counters and Timers
    public static final int CYCLE = 0xC00;
    public static final int TIME = 0xC01;
    public static final int INSTRET = 0xC02;
    public static final int HPMCOUNTER31 = 0xC1F; // Last of hpmcounter3...hpmcounter31.
    public static final int CYCLEH = 0xC80;
    public static final int TIMEH = 0xC81;
    public static final int INSTRETH = 0xC82;
    public static final int HPMCOUNTER31H = 0xC9F; // Last of hpmcounter3h...hpmcounter31h.

    // Machine Information Registers
    public static final int MVENDORID = 0xF11; // Vendor ID.
    public static final int MARCHID = 0xF12; // Architecture ID.
    public static final int MIMPID = 0xF13; // Implementation ID.
    public static final int MHARTID = 0xF14; // Hardware thread ID.

    // Sedna proprietary CSRs
    public static final int SEDNA_SWITCH_TO_XLEN32 = 0xBC0;

    private R5CSR() {
    }
}
