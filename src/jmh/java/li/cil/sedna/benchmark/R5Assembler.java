package li.cil.sedna.benchmark;

public final class R5Assembler {
    private R5Assembler() {
    }

    private static final int OP_IMM = 0b0010011;
    private static final int OP = 0b0110011;
    private static final int LOAD = 0b0000011;
    private static final int STORE = 0b0100011;
    private static final int BRANCH = 0b1100011;
    private static final int JAL = 0b1101111;
    private static final int SYSTEM = 0b1110011;
    private static final int OP_FP = 0b1010011;

    public static final int NOP = addi(0, 0, 0);
    public static final int ECALL = SYSTEM;
    public static final int MRET = (0b0011000 << 25) | (0b00010 << 20) | SYSTEM;
    public static final int WFI = (0b0001000 << 25) | (0b00101 << 20) | SYSTEM;
    public static final int SFENCE_VMA = (0b0001001 << 25) | SYSTEM;

    /** An encoding that decodes to nothing, used to provoke an illegal instruction trap. */
    public static final int ILLEGAL = 0x00000000;

    public static final int CSR_SSTATUS = 0x100;
    public static final int CSR_SATP = 0x180;
    public static final int CSR_MSTATUS = 0x300;
    public static final int CSR_MIE = 0x304;
    public static final int CSR_MTVEC = 0x305;
    public static final int CSR_MEPC = 0x341;
    public static final int CSR_STVEC = 0x105;

    public static int addi(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b000, rd, OP_IMM);
    }

    public static int add(final int rd, final int rs1, final int rs2) {
        return rType(0b0000000, rs2, rs1, 0b000, rd, OP);
    }

    public static int xor(final int rd, final int rs1, final int rs2) {
        return rType(0b0000000, rs2, rs1, 0b100, rd, OP);
    }

    public static int mul(final int rd, final int rs1, final int rs2) {
        return rType(0b0000001, rs2, rs1, 0b000, rd, OP);
    }

    /** {@code mulh}, the signed times signed high half multiply. */
    public static int mulh(final int rd, final int rs1, final int rs2) {
        return rType(0b0000001, rs2, rs1, 0b001, rd, OP);
    }

    /** {@code mulhsu}, the signed times unsigned high half multiply. */
    public static int mulhsu(final int rd, final int rs1, final int rs2) {
        return rType(0b0000001, rs2, rs1, 0b010, rd, OP);
    }

    /** {@code mulhu}, the unsigned times unsigned high half multiply. */
    public static int mulhu(final int rd, final int rs1, final int rs2) {
        return rType(0b0000001, rs2, rs1, 0b011, rd, OP);
    }

    /** {@code ld rd, imm(rs1)} */
    public static int ld(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b011, rd, LOAD);
    }

    /** {@code sd rs2, imm(rs1)} */
    public static int sd(final int rs2, final int rs1, final int imm) {
        return sType(imm, rs2, rs1, 0b011, STORE);
    }

    /** {@code lw rd, imm(rs1)} */
    public static int lw(final int rd, final int rs1, final int imm) {
        return iType(imm, rs1, 0b010, rd, LOAD);
    }

    /** {@code sw rs2, imm(rs1)} */
    public static int sw(final int rs2, final int rs1, final int imm) {
        return sType(imm, rs2, rs1, 0b010, STORE);
    }

    /** {@code fmv.d.x rd, rs1}, moving an integer register's bits into a float register. */
    public static int fmvDX(final int rd, final int rs1) {
        return rType(0b1111001, 0, rs1, 0b000, rd, OP_FP);
    }

    /** {@code fadd.d rd, rs1, rs2} with the dynamic rounding mode. */
    public static int faddD(final int rd, final int rs1, final int rs2) {
        return rType(0b0000001, rs2, rs1, 0b111, rd, OP_FP);
    }

    /** {@code beq rs1, rs2, offset} */
    public static int beq(final int rs1, final int rs2, final int offset) {
        return bType(offset, rs2, rs1, 0b000, BRANCH);
    }

    /** {@code jal rd, offset} */
    public static int jal(final int rd, final int offset) {
        return (((offset >> 20) & 0b1) << 31)
            | (((offset >> 1) & 0b11_1111_1111) << 21)
            | (((offset >> 11) & 0b1) << 20)
            | (((offset >> 12) & 0b1111_1111) << 12)
            | (rd << 7) | JAL;
    }

    public static int csrrw(final int rd, final int csr, final int rs1) {
        return iType(csr, rs1, 0b001, rd, SYSTEM);
    }

    public static int csrrs(final int rd, final int csr, final int rs1) {
        return iType(csr, rs1, 0b010, rd, SYSTEM);
    }

    public static int csrrc(final int rd, final int csr, final int rs1) {
        return iType(csr, rs1, 0b011, rd, SYSTEM);
    }

    private static int rType(final int funct7, final int rs2, final int rs1, final int funct3, final int rd, final int opcode) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int iType(final int imm, final int rs1, final int funct3, final int rd, final int opcode) {
        return ((imm & 0xFFF) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    private static int sType(final int imm, final int rs2, final int rs1, final int funct3, final int opcode) {
        return (((imm >> 5) & 0b111_1111) << 25) | (rs2 << 20) | (rs1 << 15)
            | (funct3 << 12) | ((imm & 0b1_1111) << 7) | opcode;
    }

    private static int bType(final int imm, final int rs2, final int rs1, final int funct3, final int opcode) {
        return (((imm >> 12) & 0b1) << 31) | (((imm >> 5) & 0b11_1111) << 25)
            | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
            | (((imm >> 1) & 0b1111) << 8) | (((imm >> 11) & 0b1) << 7) | opcode;
    }
}
