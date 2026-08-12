package li.cil.sedna.riscv;

@SuppressWarnings({"unused", "RedundantSuppression"})
public final class R5Assembler {
    private R5Assembler() {
    }

    // Opcodes, see instructions64.txt.
    private static final int LOAD = 0b0000011;
    private static final int LOAD_FP = 0b0000111;
    private static final int MISC_MEM = 0b0001111;
    private static final int OP_IMM = 0b0010011;
    private static final int AUIPC = 0b0010111;
    private static final int OP_IMM_32 = 0b0011011;
    private static final int STORE = 0b0100011;
    private static final int STORE_FP = 0b0100111;
    private static final int AMO = 0b0101111;
    private static final int OP = 0b0110011;
    private static final int LUI = 0b0110111;
    private static final int OP_32 = 0b0111011;
    private static final int MADD = 0b1000011;
    private static final int MSUB = 0b1000111;
    private static final int NMSUB = 0b1001011;
    private static final int NMADD = 0b1001111;
    private static final int OP_FP = 0b1010011;
    private static final int BRANCH = 0b1100011;
    private static final int JALR = 0b1100111;
    private static final int JAL = 0b1101111;
    private static final int SYSTEM = 0b1110011;

    // funct7 values shared by several OP encodings.
    private static final int FUNCT7_BASE = 0b0000000;
    private static final int FUNCT7_ALT = 0b0100000; // SUB, SRA and friends.
    private static final int FUNCT7_MULDIV = 0b0000001;

    // The fmt field of the fused multiply-add encodings, and the low two bits of the OP-FP funct7.
    private static final int FMT_S = 0b00;
    private static final int FMT_D = 0b01;

    // ------------------------------------------------------------- //
    // Ready-made encodings

    public static final int NOP = addi(0, 0, 0);

    public static final int ILLEGAL = 0x00000000;

    public static final int ECALL = SYSTEM;
    public static final int EBREAK = (0b000000000001 << 20) | SYSTEM;
    public static final int SRET = (0b0001000 << 25) | (0b00010 << 20) | SYSTEM;
    public static final int MRET = (0b0011000 << 25) | (0b00010 << 20) | SYSTEM;
    public static final int WFI = (0b0001000 << 25) | (0b00101 << 20) | SYSTEM;

    public static final int SFENCE_VMA = sfenceVma(0, 0);
    public static final int FENCE = fence(0b1111, 0b1111);
    public static final int FENCE_I = (0b001 << 12) | MISC_MEM;

    // ------------------------------------------------------------- //
    // RV32I/RV64I: control transfer

    public static int jal(final int rd, final int offset) {
        return jType(offset, rd, JAL);
    }

    public static int jalr(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b000, rd, JALR);
    }

    public static int beq(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b000, BRANCH);
    }

    public static int bne(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b001, BRANCH);
    }

    public static int blt(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b100, BRANCH);
    }

    public static int bge(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b101, BRANCH);
    }

    public static int bltu(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b110, BRANCH);
    }

    public static int bgeu(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b111, BRANCH);
    }

    // ------------------------------------------------------------- //
    // RV32I/RV64I: integer computation

    public static int lui(final int rd, final int imm) {
        return uType(imm, rd, LUI);
    }

    public static int auipc(final int rd, final int imm) {
        return uType(imm, rd, AUIPC);
    }

    public static int addi(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b000, rd, OP_IMM);
    }

    public static int slti(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b010, rd, OP_IMM);
    }

    public static int sltiu(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b011, rd, OP_IMM);
    }

    public static int xori(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b100, rd, OP_IMM);
    }

    public static int ori(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b110, rd, OP_IMM);
    }

    public static int andi(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b111, rd, OP_IMM);
    }

    public static int slli(final int rd, final int rs1, final int shamt) {
        return shiftImmediate(0b000000, shamt, rs1, 0b001, rd, OP_IMM);
    }

    public static int srli(final int rd, final int rs1, final int shamt) {
        return shiftImmediate(0b000000, shamt, rs1, 0b101, rd, OP_IMM);
    }

    public static int srai(final int rd, final int rs1, final int shamt) {
        return shiftImmediate(0b010000, shamt, rs1, 0b101, rd, OP_IMM);
    }

    public static int add(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b000, rd, OP);
    }

    public static int sub(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_ALT, rs2, rs1, 0b000, rd, OP);
    }

    public static int sll(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b001, rd, OP);
    }

    public static int slt(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b010, rd, OP);
    }

    public static int sltu(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b011, rd, OP);
    }

    public static int xor(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b100, rd, OP);
    }

    public static int srl(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b101, rd, OP);
    }

    public static int sra(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_ALT, rs2, rs1, 0b101, rd, OP);
    }

    public static int or(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b110, rd, OP);
    }

    public static int and(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b111, rd, OP);
    }

    // ------------------------------------------------------------- //
    // RV64I: 32 bit integer computation

    public static int addiw(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b000, rd, OP_IMM_32);
    }

    public static int slliw(final int rd, final int rs1, final int shamt) {
        return shiftImmediateWord(FUNCT7_BASE, shamt, rs1, 0b001, rd, OP_IMM_32);
    }

    public static int srliw(final int rd, final int rs1, final int shamt) {
        return shiftImmediateWord(FUNCT7_BASE, shamt, rs1, 0b101, rd, OP_IMM_32);
    }

    public static int sraiw(final int rd, final int rs1, final int shamt) {
        return shiftImmediateWord(FUNCT7_ALT, shamt, rs1, 0b101, rd, OP_IMM_32);
    }

    public static int addw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b000, rd, OP_32);
    }

    public static int subw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_ALT, rs2, rs1, 0b000, rd, OP_32);
    }

    public static int sllw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b001, rd, OP_32);
    }

    public static int srlw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_BASE, rs2, rs1, 0b101, rd, OP_32);
    }

    public static int sraw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_ALT, rs2, rs1, 0b101, rd, OP_32);
    }

    // ------------------------------------------------------------- //
    // RV32I/RV64I: loads and stores

    public static int lb(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b000, rd, LOAD);
    }

    public static int lh(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b001, rd, LOAD);
    }

    public static int lw(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b010, rd, LOAD);
    }

    public static int ld(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b011, rd, LOAD);
    }

    public static int lbu(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b100, rd, LOAD);
    }

    public static int lhu(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b101, rd, LOAD);
    }

    public static int lwu(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b110, rd, LOAD);
    }

    public static int sb(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b000, STORE);
    }

    public static int sh(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b001, STORE);
    }

    public static int sw(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b010, STORE);
    }

    public static int sd(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b011, STORE);
    }

    // ------------------------------------------------------------- //
    // M extension

    public static int mul(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b000, rd, OP);
    }

    public static int mulh(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b001, rd, OP);
    }

    public static int mulhsu(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b010, rd, OP);
    }

    public static int mulhu(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b011, rd, OP);
    }

    public static int div(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b100, rd, OP);
    }

    public static int divu(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b101, rd, OP);
    }

    public static int rem(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b110, rd, OP);
    }

    public static int remu(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b111, rd, OP);
    }

    public static int mulw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b000, rd, OP_32);
    }

    public static int divw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b100, rd, OP_32);
    }

    public static int divuw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b101, rd, OP_32);
    }

    public static int remw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b110, rd, OP_32);
    }

    public static int remuw(final int rd, final int rs1, final int rs2) {
        return rType(FUNCT7_MULDIV, rs2, rs1, 0b111, rd, OP_32);
    }

    // ------------------------------------------------------------- //
    // A extension

    public static int lrW(final int rd, final int rs1) {
        return amoType(0b00010, 0, rs1, 0b010, rd);
    }

    public static int scW(final int rd, final int rs1, final int rs2) {
        return amoType(0b00011, rs2, rs1, 0b010, rd);
    }

    public static int amoswapW(final int rd, final int rs1, final int rs2) {
        return amoType(0b00001, rs2, rs1, 0b010, rd);
    }

    public static int amoaddW(final int rd, final int rs1, final int rs2) {
        return amoType(0b00000, rs2, rs1, 0b010, rd);
    }

    public static int lrD(final int rd, final int rs1) {
        return amoType(0b00010, 0, rs1, 0b011, rd);
    }

    public static int scD(final int rd, final int rs1, final int rs2) {
        return amoType(0b00011, rs2, rs1, 0b011, rd);
    }

    public static int amoswapD(final int rd, final int rs1, final int rs2) {
        return amoType(0b00001, rs2, rs1, 0b011, rd);
    }

    public static int amoaddD(final int rd, final int rs1, final int rs2) {
        return amoType(0b00000, rs2, rs1, 0b011, rd);
    }

    // ------------------------------------------------------------- //
    // F and D extensions: loads and stores

    public static int flw(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b010, rd, LOAD_FP);
    }

    public static int fld(final int rd, final int rs1, final int offset) {
        return iType(offset, rs1, 0b011, rd, LOAD_FP);
    }

    public static int fsw(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b010, STORE_FP);
    }

    public static int fsd(final int rs2, final int rs1, final int offset) {
        return sType(offset, rs2, rs1, 0b011, STORE_FP);
    }

    // ------------------------------------------------------------- //
    // F and D extensions: arithmetic

    public static int faddS(final int rd, final int rs1, final int rs2) {
        return faddS(rd, rs1, rs2, R5.FCSR_FRM_DYN);
    }

    public static int faddS(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0000000, rs2, rs1, rm, rd);
    }

    public static int fsubS(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0000100, rs2, rs1, rm, rd);
    }

    public static int fmulS(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0001000, rs2, rs1, rm, rd);
    }

    public static int fdivS(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0001100, rs2, rs1, rm, rd);
    }

    public static int fsqrtS(final int rd, final int rs1) {
        return fsqrtS(rd, rs1, R5.FCSR_FRM_DYN);
    }

    public static int fsqrtS(final int rd, final int rs1, final int rm) {
        return fpType(0b0101100, 0b00000, rs1, rm, rd);
    }

    public static int faddD(final int rd, final int rs1, final int rs2) {
        return faddD(rd, rs1, rs2, R5.FCSR_FRM_DYN);
    }

    public static int faddD(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0000001, rs2, rs1, rm, rd);
    }

    public static int fsubD(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0000101, rs2, rs1, rm, rd);
    }

    public static int fmulD(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0001001, rs2, rs1, rm, rd);
    }

    public static int fdivD(final int rd, final int rs1, final int rs2, final int rm) {
        return fpType(0b0001101, rs2, rs1, rm, rd);
    }

    public static int fsqrtD(final int rd, final int rs1, final int rm) {
        return fpType(0b0101101, 0b00000, rs1, rm, rd);
    }

    // ------------------------------------------------------------- //
    // F and D extensions: fused multiply-add

    public static int fmaddS(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_S, rs2, rs1, rm, rd, MADD);
    }

    public static int fmsubS(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_S, rs2, rs1, rm, rd, MSUB);
    }

    public static int fnmsubS(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_S, rs2, rs1, rm, rd, NMSUB);
    }

    public static int fnmaddS(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_S, rs2, rs1, rm, rd, NMADD);
    }

    public static int fmaddD(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_D, rs2, rs1, rm, rd, MADD);
    }

    public static int fmsubD(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_D, rs2, rs1, rm, rd, MSUB);
    }

    public static int fnmsubD(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_D, rs2, rs1, rm, rd, NMSUB);
    }

    public static int fnmaddD(final int rd, final int rs1, final int rs2, final int rs3, final int rm) {
        return r4Type(rs3, FMT_D, rs2, rs1, rm, rd, NMADD);
    }

    // ------------------------------------------------------------- //
    // F and D extensions: comparison, conversion and moves

    public static int feqS(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010000, rs2, rs1, 0b010, rd);
    }

    public static int fltS(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010000, rs2, rs1, 0b001, rd);
    }

    public static int fleS(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010000, rs2, rs1, 0b000, rd);
    }

    public static int feqD(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010001, rs2, rs1, 0b010, rd);
    }

    public static int fltD(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010001, rs2, rs1, 0b001, rd);
    }

    public static int fleD(final int rd, final int rs1, final int rs2) {
        return fpType(0b1010001, rs2, rs1, 0b000, rd);
    }

    public static int fcvtWS(final int rd, final int rs1) {
        return fcvtWS(rd, rs1, R5.FCSR_FRM_DYN);
    }

    public static int fcvtWS(final int rd, final int rs1, final int rm) {
        return fpType(0b1100000, 0b00000, rs1, rm, rd);
    }

    public static int fcvtLD(final int rd, final int rs1) {
        return fcvtLD(rd, rs1, R5.FCSR_FRM_DYN);
    }

    public static int fcvtLD(final int rd, final int rs1, final int rm) {
        return fpType(0b1100001, 0b00010, rs1, rm, rd);
    }

    public static int fcvtSD(final int rd, final int rs1, final int rm) {
        return fpType(0b0100000, 0b00001, rs1, rm, rd);
    }

    public static int fcvtDS(final int rd, final int rs1, final int rm) {
        return fpType(0b0100001, 0b00000, rs1, rm, rd);
    }

    public static int fmvWX(final int rd, final int rs1) {
        return fpType(0b1111000, 0b00000, rs1, 0b000, rd);
    }

    public static int fmvXW(final int rd, final int rs1) {
        return fpType(0b1110000, 0b00000, rs1, 0b000, rd);
    }

    public static int fmvDX(final int rd, final int rs1) {
        return fpType(0b1111001, 0b00000, rs1, 0b000, rd);
    }

    public static int fmvXD(final int rd, final int rs1) {
        return fpType(0b1110001, 0b00000, rs1, 0b000, rd);
    }

    // ------------------------------------------------------------- //
    // Zicsr

    public static int csrrw(final int rd, final int csr, final int rs1) {
        return csrType(csr, reg(rs1), 0b001, rd);
    }

    public static int csrrs(final int rd, final int csr, final int rs1) {
        return csrType(csr, reg(rs1), 0b010, rd);
    }

    public static int csrrc(final int rd, final int csr, final int rs1) {
        return csrType(csr, reg(rs1), 0b011, rd);
    }

    public static int csrrwi(final int rd, final int csr, final int uimm) {
        return csrType(csr, checkUnsigned(uimm, 5, "uimm"), 0b101, rd);
    }

    public static int csrrsi(final int rd, final int csr, final int uimm) {
        return csrType(csr, checkUnsigned(uimm, 5, "uimm"), 0b110, rd);
    }

    public static int csrrci(final int rd, final int csr, final int uimm) {
        return csrType(csr, checkUnsigned(uimm, 5, "uimm"), 0b111, rd);
    }

    // ------------------------------------------------------------- //
    // Privileged and memory ordering

    public static int sfenceVma(final int rs1) {
        return sfenceVma(rs1, 0);
    }

    public static int sfenceVma(final int rs1, final int rs2) {
        return (0b0001001 << 25) | (reg(rs2) << 20) | (reg(rs1) << 15) | SYSTEM;
    }

    public static int fence(final int predecessor, final int successor) {
        return (checkUnsigned(predecessor, 4, "predecessor") << 24)
            | (checkUnsigned(successor, 4, "successor") << 20)
            | MISC_MEM;
    }

    // ------------------------------------------------------------- //
    // C extension. These return 16 bit encodings in the low half of the int.

    public static final int C_NOP = 0x0001;

    public static int cAddi(final int rd, final int imm) {
        if (imm == 0) {
            throw new IllegalArgumentException("c.addi requires a non-zero immediate");
        }
        final int nzimm = checkSigned(imm, 6, "imm");
        return (((nzimm >> 5) & 0b1) << 12) | (regNonZero(rd) << 7) | ((nzimm & 0b1_1111) << 2) | 0b01;
    }

    public static int cMv(final int rd, final int rs2) {
        return (0b1000 << 12) | (regNonZero(rd) << 7) | (regNonZero(rs2) << 2) | 0b10;
    }

    // ------------------------------------------------------------- //
    // Instruction formats

    private static int rType(final int funct7, final int rs2, final int rs1, final int funct3, final int rd, final int opcode) {
        return (funct7 << 25) | (reg(rs2) << 20) | (reg(rs1) << 15) | (funct3 << 12) | (reg(rd) << 7) | opcode;
    }

    private static int amoType(final int funct5, final int rs2, final int rs1, final int funct3, final int rd) {
        return (funct5 << 27) | (reg(rs2) << 20) | (reg(rs1) << 15) | (funct3 << 12) | (reg(rd) << 7) | AMO;
    }

    private static int r4Type(final int rs3, final int fmt, final int rs2, final int rs1, final int rm, final int rd, final int opcode) {
        return (reg(rs3) << 27) | (fmt << 25) | (reg(rs2) << 20) | (reg(rs1) << 15)
            | (roundingMode(rm) << 12) | (reg(rd) << 7) | opcode;
    }

    private static int iType(final int imm, final int rs1, final int funct3, final int rd, final int opcode) {
        return (checkSigned(imm, 12, "imm") << 20) | (reg(rs1) << 15) | (funct3 << 12) | (reg(rd) << 7) | opcode;
    }

    private static int sType(final int imm, final int rs2, final int rs1, final int funct3, final int opcode) {
        checkSigned(imm, 12, "imm");
        return (((imm >> 5) & 0b111_1111) << 25) | (reg(rs2) << 20) | (reg(rs1) << 15)
            | (funct3 << 12) | ((imm & 0b1_1111) << 7) | opcode;
    }

    private static int bType(final int imm, final int rs2, final int rs1, final int funct3, final int opcode) {
        checkSigned(imm, 13, "offset");
        checkEven(imm, "offset");
        return (((imm >> 12) & 0b1) << 31) | (((imm >> 5) & 0b11_1111) << 25)
            | (reg(rs2) << 20) | (reg(rs1) << 15) | (funct3 << 12)
            | (((imm >> 1) & 0b1111) << 8) | (((imm >> 11) & 0b1) << 7) | opcode;
    }

    private static int uType(final int imm, final int rd, final int opcode) {
        return (checkUnsigned(imm, 20, "imm") << 12) | (reg(rd) << 7) | opcode;
    }

    private static int jType(final int imm, final int rd, final int opcode) {
        checkSigned(imm, 21, "offset");
        checkEven(imm, "offset");
        return (((imm >> 20) & 0b1) << 31)
            | (((imm >> 1) & 0b11_1111_1111) << 21)
            | (((imm >> 11) & 0b1) << 20)
            | (((imm >> 12) & 0b1111_1111) << 12)
            | (reg(rd) << 7) | opcode;
    }

    private static int fpType(final int funct7, final int rs2, final int rs1, final int rm, final int rd) {
        return (funct7 << 25) | (rs2 << 20) | (reg(rs1) << 15) | (roundingMode(rm) << 12) | (reg(rd) << 7) | OP_FP;
    }

    private static int csrType(final int csr, final int rs1, final int funct3, final int rd) {
        return (checkUnsigned(csr, 12, "csr") << 20) | (rs1 << 15) | (funct3 << 12) | (reg(rd) << 7) | SYSTEM;
    }

    private static int shiftImmediate(final int funct6, final int shamt, final int rs1, final int funct3, final int rd, final int opcode) {
        return (funct6 << 26) | (checkUnsigned(shamt, 6, "shamt") << 20)
            | (reg(rs1) << 15) | (funct3 << 12) | (reg(rd) << 7) | opcode;
    }

    private static int shiftImmediateWord(final int funct7, final int shamt, final int rs1, final int funct3, final int rd, final int opcode) {
        return (funct7 << 25) | (checkUnsigned(shamt, 5, "shamt") << 20)
            | (reg(rs1) << 15) | (funct3 << 12) | (reg(rd) << 7) | opcode;
    }

    // ------------------------------------------------------------- //
    // Argument validation

    private static int reg(final int register) {
        if (register < 0 || register > 31) {
            throw new IllegalArgumentException("Not a valid register: " + register);
        }
        return register;
    }

    private static int regNonZero(final int register) {
        if (register < 1 || register > 31) {
            throw new IllegalArgumentException("Not a valid non-zero register: " + register);
        }
        return register;
    }

    private static int roundingMode(final int rm) {
        if (rm < 0 || rm > 0b111) {
            throw new IllegalArgumentException("Not a valid rounding mode: " + rm);
        }
        return rm;
    }

    private static int checkSigned(final int value, final int bits, final String name) {
        if (value < -(1 << (bits - 1)) || value >= (1 << (bits - 1))) {
            throw new IllegalArgumentException(String.format("%s does not fit into %d signed bits: %d", name, bits, value));
        }
        return value & ((1 << bits) - 1);
    }

    private static int checkUnsigned(final int value, final int bits, final String name) {
        if (value < 0 || value >= (1 << bits)) {
            throw new IllegalArgumentException(String.format("%s does not fit into %d unsigned bits: %d", name, bits, value));
        }
        return value;
    }

    private static void checkEven(final int value, final String name) {
        if ((value & 1) != 0) {
            throw new IllegalArgumentException(String.format("%s must be even: %d", name, value));
        }
    }
}
