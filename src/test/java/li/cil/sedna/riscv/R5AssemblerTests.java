package li.cil.sedna.riscv;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionType;
import li.cil.sedna.instruction.argument.InstructionArgument;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.*;

public final class R5AssemblerTests {
    @Test
    public void controlTransferInstructions() {
        assertInstruction(jal(1, -8), "JAL", "rd", 1, "imm", -8);
        assertInstruction(jal(0, 0x0FFFFE), "JAL", "rd", 0, "imm", 0x0FFFFE);
        assertInstruction(jalr(1, 2, -4), "JALR", "rd", 1, "rs1", 2, "imm", -4);

        assertInstruction(beq(1, 2, -4), "BEQ", "rs1", 1, "rs2", 2, "imm", -4);
        assertInstruction(bne(1, 2, 0xFFE), "BNE", "rs1", 1, "rs2", 2, "imm", 0xFFE);
        assertInstruction(blt(3, 4, 8), "BLT", "rs1", 3, "rs2", 4, "imm", 8);
        assertInstruction(bge(3, 4, 8), "BGE", "rs1", 3, "rs2", 4, "imm", 8);
        assertInstruction(bltu(3, 4, 8), "BLTU", "rs1", 3, "rs2", 4, "imm", 8);
        assertInstruction(bgeu(3, 4, 8), "BGEU", "rs1", 3, "rs2", 4, "imm", 8);
    }

    @Test
    public void integerComputationInstructions() {
        assertInstruction(lui(5, 0x12345), "LUI", "rd", 5);
        assertInstruction(lui(5, 0xFFFFF), "LUI", "rd", 5);
        assertInstruction(auipc(5, 0x12345), "AUIPC", "rd", 5);

        assertInstruction(addi(1, 2, -2048), "ADDI", "rd", 1, "rs1", 2, "imm", -2048);
        assertInstruction(addi(1, 2, 2047), "ADDI", "rd", 1, "rs1", 2, "imm", 2047);
        assertInstruction(slti(1, 2, 3), "SLTI", "rd", 1, "rs1", 2, "imm", 3);
        assertInstruction(sltiu(1, 2, 3), "SLTIU", "rd", 1, "rs1", 2, "imm", 3);
        assertInstruction(xori(1, 2, 3), "XORI", "rd", 1, "rs1", 2, "imm", 3);
        assertInstruction(ori(1, 2, 3), "ORI", "rd", 1, "rs1", 2, "imm", 3);
        assertInstruction(andi(1, 2, 3), "ANDI", "rd", 1, "rs1", 2, "imm", 3);

        assertInstruction(slli(1, 2, 63), "SLLI", "rd", 1, "rs1", 2, "shamt", 63);
        assertInstruction(srli(1, 2, 63), "SRLI", "rd", 1, "rs1", 2, "shamt", 63);
        assertInstruction(srai(1, 2, 63), "SRAI", "rd", 1, "rs1", 2, "shamt", 63);

        assertInstruction(add(1, 2, 3), "ADD", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sub(1, 2, 3), "SUB", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sll(1, 2, 3), "SLL", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(slt(1, 2, 3), "SLT", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sltu(1, 2, 3), "SLTU", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(xor(1, 2, 3), "XOR", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(srl(1, 2, 3), "SRL", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sra(1, 2, 3), "SRA", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(or(1, 2, 3), "OR", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(and(1, 2, 3), "AND", "rd", 1, "rs1", 2, "rs2", 3);
    }

    @Test
    public void wordWidthIntegerComputationInstructions() {
        assertInstruction(addiw(1, 2, -3), "ADDIW", "rd", 1, "rs1", 2, "imm", -3);
        assertInstruction(slliw(1, 2, 31), "SLLIW", "rd", 1, "rs1", 2, "shamt", 31);
        assertInstruction(srliw(1, 2, 31), "SRLIW", "rd", 1, "rs1", 2, "shamt", 31);
        assertInstruction(sraiw(1, 2, 31), "SRAIW", "rd", 1, "rs1", 2, "shamt", 31);

        assertInstruction(addw(1, 2, 3), "ADDW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(subw(1, 2, 3), "SUBW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sllw(1, 2, 3), "SLLW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(srlw(1, 2, 3), "SRLW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(sraw(1, 2, 3), "SRAW", "rd", 1, "rs1", 2, "rs2", 3);
    }

    @Test
    public void loadAndStoreInstructions() {
        assertInstruction(lb(1, 2, -4), "LB", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(lh(1, 2, -4), "LH", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(lw(1, 2, -4), "LW", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(ld(1, 2, -4), "LD", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(lbu(1, 2, 4), "LBU", "rd", 1, "rs1", 2, "imm", 4);
        assertInstruction(lhu(1, 2, 4), "LHU", "rd", 1, "rs1", 2, "imm", 4);
        assertInstruction(lwu(1, 2, 4), "LWU", "rd", 1, "rs1", 2, "imm", 4);

        // Stores take the source register first, matching the assembly syntax.
        assertInstruction(sb(1, 2, -4), "SB", "rs2", 1, "rs1", 2, "imm", -4);
        assertInstruction(sh(1, 2, -4), "SH", "rs2", 1, "rs1", 2, "imm", -4);
        assertInstruction(sw(1, 2, -4), "SW", "rs2", 1, "rs1", 2, "imm", -4);
        assertInstruction(sd(1, 2, -4), "SD", "rs2", 1, "rs1", 2, "imm", -4);
        assertInstruction(sd(1, 2, 2047), "SD", "rs2", 1, "rs1", 2, "imm", 2047);
    }

    @Test
    public void multiplyAndDivideInstructions() {
        assertInstruction(mul(1, 2, 3), "MUL", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(mulh(1, 2, 3), "MULH", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(mulhsu(1, 2, 3), "MULHSU", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(mulhu(1, 2, 3), "MULHU", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(div(1, 2, 3), "DIV", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(divu(1, 2, 3), "DIVU", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(rem(1, 2, 3), "REM", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(remu(1, 2, 3), "REMU", "rd", 1, "rs1", 2, "rs2", 3);

        assertInstruction(mulw(1, 2, 3), "MULW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(divw(1, 2, 3), "DIVW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(divuw(1, 2, 3), "DIVUW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(remw(1, 2, 3), "REMW", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(remuw(1, 2, 3), "REMUW", "rd", 1, "rs1", 2, "rs2", 3);
    }

    @Test
    public void floatingPointLoadAndStoreInstructions() {
        assertInstruction(flw(1, 2, -4), "FLW", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(fld(1, 2, -4), "FLD", "rd", 1, "rs1", 2, "imm", -4);
        assertInstruction(fsw(1, 2, -4), "FSW", "rs2", 1, "rs1", 2, "imm", -4);
        assertInstruction(fsd(1, 2, -4), "FSD", "rs2", 1, "rs1", 2, "imm", -4);
    }

    @Test
    public void floatingPointArithmeticInstructions() {
        assertInstruction(faddS(1, 2, 3), "FADD.S", "rd", 1, "rs1", 2, "rs2", 3, "rm", R5.FCSR_FRM_DYN);
        assertInstruction(faddS(1, 2, 3, R5.FCSR_FRM_RTZ), "FADD.S", "rm", R5.FCSR_FRM_RTZ);
        assertInstruction(fsubS(1, 2, 3, R5.FCSR_FRM_RNE), "FSUB.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fmulS(1, 2, 3, R5.FCSR_FRM_RNE), "FMUL.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fdivS(1, 2, 3, R5.FCSR_FRM_RNE), "FDIV.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fsqrtS(1, 2), "FSQRT.S", "rd", 1, "rs1", 2, "rm", R5.FCSR_FRM_DYN);

        assertInstruction(faddD(1, 2, 3), "FADD.D", "rd", 1, "rs1", 2, "rs2", 3, "rm", R5.FCSR_FRM_DYN);
        assertInstruction(fsubD(1, 2, 3, R5.FCSR_FRM_RNE), "FSUB.D", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fmulD(1, 2, 3, R5.FCSR_FRM_RNE), "FMUL.D", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fdivD(1, 2, 3, R5.FCSR_FRM_RNE), "FDIV.D", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fsqrtD(1, 2, R5.FCSR_FRM_RNE), "FSQRT.D", "rd", 1, "rs1", 2);
    }

    @Test
    public void fusedMultiplyAddInstructions() {
        assertInstruction(fmaddS(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FMADD.S", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);
        assertInstruction(fmsubS(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FMSUB.S", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);
        assertInstruction(fnmsubS(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FNMSUB.S", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);
        assertInstruction(fnmaddS(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FNMADD.S", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);

        assertInstruction(fmaddD(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FMADD.D", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);
        assertInstruction(fmsubD(1, 2, 3, 4, R5.FCSR_FRM_RNE), "FMSUB.D", "rd", 1, "rs1", 2, "rs2", 3, "rs3", 4);
        assertInstruction(fnmsubD(1, 2, 3, 4, R5.FCSR_FRM_RUP), "FNMSUB.D", "rd", 1, "rm", R5.FCSR_FRM_RUP);
        assertInstruction(fnmaddD(1, 2, 3, 4, R5.FCSR_FRM_RDN), "FNMADD.D", "rd", 1, "rm", R5.FCSR_FRM_RDN);
    }

    @Test
    public void floatingPointCompareConvertAndMoveInstructions() {
        assertInstruction(feqS(1, 2, 3), "FEQ.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fltS(1, 2, 3), "FLT.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fleS(1, 2, 3), "FLE.S", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(feqD(1, 2, 3), "FEQ.D", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fltD(1, 2, 3), "FLT.D", "rd", 1, "rs1", 2, "rs2", 3);
        assertInstruction(fleD(1, 2, 3), "FLE.D", "rd", 1, "rs1", 2, "rs2", 3);

        assertInstruction(fcvtWS(1, 2), "FCVT.W.S", "rd", 1, "rs1", 2);
        assertInstruction(fcvtLD(1, 2), "FCVT.L.D", "rd", 1, "rs1", 2);
        assertInstruction(fcvtSD(1, 2, R5.FCSR_FRM_RNE), "FCVT.S.D", "rd", 1, "rs1", 2);
        assertInstruction(fcvtDS(1, 2, R5.FCSR_FRM_RNE), "FCVT.D.S", "rd", 1, "rs1", 2);

        assertInstruction(fmvWX(1, 2), "FMV.W.X", "rd", 1, "rs1", 2);
        assertInstruction(fmvXW(1, 2), "FMV.X.W", "rd", 1, "rs1", 2);
        assertInstruction(fmvDX(1, 2), "FMV.D.X", "rd", 1, "rs1", 2);
        assertInstruction(fmvXD(1, 2), "FMV.X.D", "rd", 1, "rs1", 2);
    }

    @Test
    public void csrInstructions() {
        assertInstruction(csrrw(1, R5CSR.MSTATUS, 2), "CSRRW", "rd", 1, "rs1", 2, "csr", R5CSR.MSTATUS);
        assertInstruction(csrrs(1, R5CSR.SATP, 2), "CSRRS", "rd", 1, "rs1", 2, "csr", R5CSR.SATP);
        assertInstruction(csrrc(1, R5CSR.MIE, 2), "CSRRC", "rd", 1, "rs1", 2, "csr", R5CSR.MIE);

        // The immediate forms put the immediate where the source register otherwise goes.
        assertInstruction(csrrwi(1, R5CSR.MTVEC, 31), "CSRRWI", "rd", 1, "rs1", 31, "csr", R5CSR.MTVEC);
        assertInstruction(csrrsi(1, R5CSR.MTVEC, 31), "CSRRSI", "rd", 1, "rs1", 31, "csr", R5CSR.MTVEC);
        assertInstruction(csrrci(1, R5CSR.MTVEC, 31), "CSRRCI", "rd", 1, "rs1", 31, "csr", R5CSR.MTVEC);

        // The highest CSR address must not bleed into the funct3 field.
        assertInstruction(csrrs(1, 0xFFF, 0), "CSRRS", "csr", 0xFFF);
    }

    @Test
    public void systemAndMemoryOrderingInstructions() {
        assertInstruction(ECALL, "ECALL");
        assertInstruction(EBREAK, "EBREAK");
        assertInstruction(SRET, "SRET");
        assertInstruction(MRET, "MRET");
        assertInstruction(WFI, "WFI");
        assertInstruction(FENCE, "FENCE");
        assertInstruction(FENCE_I, "FENCE.I");

        assertInstruction(SFENCE_VMA, "SFENCE.VMA", "rs1", 0, "rs2", 0);
        assertInstruction(sfenceVma(3), "SFENCE.VMA", "rs1", 3, "rs2", 0);
        assertInstruction(sfenceVma(3, 4), "SFENCE.VMA", "rs1", 3, "rs2", 4);
    }

    @Test
    public void nopIsAnAddiThatDoesNothing() {
        assertInstruction(NOP, "ADDI", "rd", 0, "rs1", 0, "imm", 0);
    }

    @Test
    public void illegalDoesNotDecode() {
        final InstructionDeclaration declaration = R5Instructions.getDecoderTree().query(ILLEGAL);
        if (declaration != null) {
            assertEquals(InstructionType.ILLEGAL, declaration.type);
        }
    }

    @Test
    public void outOfRangeOperandsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> addi(32, 0, 0), "register above x31");
        assertThrows(IllegalArgumentException.class, () -> addi(0, -1, 0), "negative register");
        assertThrows(IllegalArgumentException.class, () -> addi(1, 2, 2048), "immediate too large");
        assertThrows(IllegalArgumentException.class, () -> addi(1, 2, -2049), "immediate too small");
        assertThrows(IllegalArgumentException.class, () -> sd(1, 2, 2048), "store offset too large");
        assertThrows(IllegalArgumentException.class, () -> bne(1, 2, 0x1000), "branch offset too large");
        assertThrows(IllegalArgumentException.class, () -> bne(1, 2, 3), "odd branch offset");
        assertThrows(IllegalArgumentException.class, () -> jal(0, 0x100000), "jump offset too large");
        assertThrows(IllegalArgumentException.class, () -> jal(0, 1), "odd jump offset");
        assertThrows(IllegalArgumentException.class, () -> csrrs(1, 0x1000, 0), "csr address too large");
        assertThrows(IllegalArgumentException.class, () -> slli(1, 2, 64), "shift amount too large");
        assertThrows(IllegalArgumentException.class, () -> slliw(1, 2, 32), "word shift amount too large");
        assertThrows(IllegalArgumentException.class, () -> csrrwi(1, R5CSR.MTVEC, 32), "csr immediate too large");
        assertThrows(IllegalArgumentException.class, () -> lui(1, 0x100000), "upper immediate too large");
        assertThrows(IllegalArgumentException.class, () -> lui(1, -1), "upper immediate is a raw field, not a value");
    }

    // ------------------------------------------------------------- //

    private static void assertInstruction(final int instruction, final String name, final Object... arguments) {
        final InstructionDeclaration declaration = R5Instructions.getDecoderTree().query(instruction);
        assertNotNull(declaration, () -> String.format("%08x does not decode to any instruction", instruction));
        assertEquals(name, declaration.name,
                () -> String.format("%08x was assembled as %s but decodes as %s",
                        instruction, name, R5Disassembler.disassemble(instruction)));

        for (int i = 0; i < arguments.length; i += 2) {
            final String argumentName = (String) arguments[i];
            final int expected = (Integer) arguments[i + 1];

            final InstructionArgument argument = declaration.arguments.get(argumentName);
            assertNotNull(argument, () -> name + " has no argument named " + argumentName);
            assertEquals(expected, argument.get(instruction),
                    () -> String.format("%s of %s", argumentName, R5Disassembler.disassemble(instruction)));
        }
    }
}
