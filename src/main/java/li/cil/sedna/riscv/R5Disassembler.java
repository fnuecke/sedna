package li.cil.sedna.riscv;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import li.cil.sedna.instruction.InstructionDeclaration;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class R5Disassembler {
    private static final String ILLEGAL_INSTRUCTION = "ILLEGAL";
    private static final String HINT = "HINT";
    private static final int INST_WIDTH = 12;

    private static final String[] REGISTER_NAME = {
            "zero", "ra", "sp", "gp", "tp",
            "t0", "t1", "t2",
            "s0", "s1",
            "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7",
            "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11",
            "t3", "t4", "t5", "t6",
    };

    private static final int REG_RA = ArrayUtils.indexOf(REGISTER_NAME, "ra");

    private static final Int2ObjectArrayMap<String> CSR_NAME = new Int2ObjectArrayMap<>(Stream.of(new Object[][]{
            {0x000, "ustatus"},
            {0x004, "uie"},
            {0x005, "utvec"},

            {0x040, "uscratch"},
            {0x041, "uepc"},
            {0x042, "ucause"},
            {0x043, "utval"},
            {0x044, "uip"},

            {0x001, "fflags"},
            {0x002, "frm"},
            {0x003, "fcsr"},

            {0xC00, "cycle"},
            {0xC01, "time"},
            {0xC02, "instret"},

            {0xC80, "cycleh"},
            {0xC81, "timeh"},
            {0xC82, "instreth"},

            {0x100, "sstatus"},
            {0x102, "sedeleg"},
            {0x103, "sideleg"},
            {0x104, "sie"},
            {0x105, "stvec"},
            {0x106, "scountern"},

            {0x140, "sscratch"},
            {0x141, "sepc"},
            {0x142, "scause"},
            {0x143, "stval"},
            {0x144, "sip"},

            {0x180, "satp"},

            {0x600, "hstatus"},
            {0x602, "hedeleg"},
            {0x603, "hideleg"},
            {0x604, "hie"},
            {0x606, "hcounteren"},
            {0x607, "hgeie"},

            {0x643, "htval"},
            {0x644, "hip"},
            {0x645, "hvip"},
            {0x64A, "htinst"},
            {0xE12, "hgeip"},

            {0x680, "hgatp"},

            {0x605, "htimedelta"},
            {0x615, "htimedeltah"},

            {0x200, "vsstatus"},
            {0x204, "vsie"},
            {0x205, "vstvec"},
            {0x240, "vsscratch"},
            {0x241, "vsepc"},
            {0x242, "vscause"},
            {0x243, "vstval"},
            {0x244, "vsip"},
            {0x280, "vsatp"},

            {0xF11, "mvendorid"},
            {0xF12, "marchid"},
            {0xF13, "mimpid"},
            {0xF14, "mhartid"},

            {0x300, "mstatus"},
            {0x301, "misa"},
            {0x302, "medeleg"},
            {0x303, "mideleg"},
            {0x304, "mie"},
            {0x305, "mtvec"},
            {0x306, "mcounteren"},
            {0x310, "mstatush"},

            {0x340, "mscratch"},
            {0x341, "mepc"},
            {0x342, "mcause"},
            {0x343, "mtval"},
            {0x344, "mip"},
            {0x34A, "mtinst"},
            {0x34B, "mtval2"},

            {0xB00, "mcycle"},
            {0xB02, "minstret"},
            {0xB80, "mcycleh"},
            {0xB82, "minstreth"},

            {0x320, "mcounterhibit"},

            {0x7A0, "tselect"},
            {0x7A1, "tdata1"},
            {0x7A2, "tdata2"},
            {0x7A3, "tdata3"},

            {0x7B0, "dcsr"},
            {0x7B1, "dpc"},
            {0x7B2, "dscratch0"},
            {0x7B3, "dscratch1"},
    }).collect(Collectors.toMap(kvp -> (Integer) kvp[0], kvp -> (String) kvp[1])));

    private static final Object2IntArrayMap<String> ARGUMENT_INDEX = new Object2IntArrayMap<>(Stream.of(new Object[][]{
            {"rd", 0},
            {"rs1", 1},
            {"rs2", 2},
            {"rs3", 3},
            {"shamt", 5},
            {"csr", 5},
            {"imm", 6},
            {"rm", 7},
    }).collect(Collectors.toMap(kvp -> (String) kvp[0], kvp -> (Integer) kvp[1])));

    private static final String[] REGISTER_ARGUMENTS = {"rd", "rs1", "rs2", "rs3"};

    private static final FormatPattern[] PATTERNS = {
            new FormatPattern("JAL", "J", "%imm").withFilter("rd", 0),
            new FormatPattern("JALR", "RET").withFilter("rd", 0).withFilter("rs1", REG_RA).withFilter("imm", 0),
            new FormatPattern("JALR", "JR", "%rs1").withFilter("rd", 0).withFilter("imm", 0),
            new FormatPattern("JALR", "JR", "%imm(%rs1)").withFilter("rd", 0),
            new FormatPattern("JALR", "JALR", "%rs1").withFilter("rd", REG_RA).withFilter("imm", 0),

            new FormatPattern("ADD", "MV", "%rd, %rs1").withFilter("rs2", 0),
            new FormatPattern("ADD", "MV", "%rd, %rs2").withFilter("rs1", 0),
            new FormatPattern("ADDI", "LI", "%rd, %imm").withFilter("rs1", 0),
            new FormatPattern("ADDI", "MV", "%rd, %rs1").withFilter("imm", 0),
            new FormatPattern("ADDIW", "SEXT.W", "%rd, %rs1").withFilter("imm", 0),
            new FormatPattern("XOR", "NOT", "%rd, %rs1").withFilter("rs2", -1),

            new FormatPattern("BGE", "BGEZ", "%rs1, %imm").withFilter("rs2", 0),
            new FormatPattern("BNE", "BNEZ", "%rs1, %imm").withFilter("rs2", 0),
            new FormatPattern("BEQ", "BEQZ", "%rs1, %imm").withFilter("rs2", 0),

            new FormatPattern("LD", "LD", "%rd, %imm(%rs1)"),
            new FormatPattern("SD", "SD", "%rs2, %imm(%rs1)"),
            new FormatPattern("LW", "LW", "%rd, %imm(%rs1)"),
            new FormatPattern("SW", "SW", "%rs2, %imm(%rs1)"),
            new FormatPattern("LH", "LH", "%rd, %imm(%rs1)"),
            new FormatPattern("SH", "SH", "%rs2, %imm(%rs1)"),
            new FormatPattern("LB", "LB", "%rd, %imm(%rs1)"),
            new FormatPattern("SB", "SB", "%rs2, %imm(%rs1)"),
    };

    public static String disassemble(final int instruction) {
        final StringBuffer sb = new StringBuffer();

        final InstructionDeclaration declaration = R5Instructions.getDecoderTree().query(instruction);
        if (declaration == null) {
            sb
                    .append(StringUtils.leftPad(Integer.toHexString(instruction), 8, '0'))
                    .append("    ")
                    .append(ILLEGAL_INSTRUCTION);
            return sb.toString();
        }

        final int instructionMask = (int) ((1L << (declaration.size * 8)) - 1);
        sb
                .append(StringUtils.leftPad(StringUtils.leftPad(Integer.toHexString(instruction & instructionMask), declaration.size, '0'), 8))
                .append("    ");

        switch (declaration.type) {
            case HINT:
                sb.append(HINT);
                return sb.toString();
            case ILLEGAL:
                sb.append(ILLEGAL_INSTRUCTION);
                return sb.toString();
        }

        for (final FormatPattern pattern : PATTERNS) {
            if (pattern.matches(instruction, declaration)) {
                pattern.format(instruction, declaration, sb);
                return sb.toString();
            }
        }

        sb.append(StringUtils.rightPad(declaration.name, INST_WIDTH));

        final String[] argumentNames = declaration.arguments.keySet()
                .stream().sorted(Comparator.comparingInt(R5Disassembler::argIndex))
                .toArray(String[]::new);
        boolean isFirst = true;
        for (final String argName : argumentNames) {
            if (!isFirst) {
                sb.append(", ");
            }
            isFirst = false;

            sb.append(arg2s(instruction, declaration, argName));
        }

        return sb.toString();
    }

    private static String arg2s(final int instruction, final InstructionDeclaration declaration, final String argName) {
        final int argValue = declaration.arguments.get(argName).get(instruction);
        if (ArrayUtils.contains(REGISTER_ARGUMENTS, argName)) {
            return reg(argValue);
        } else if ("csr".equals(argName)) {
            return csr2n(argValue);
        } else if (argValue < 0) {
            return String.valueOf(argValue);
        } else {
            return String.format("0x%x", argValue);
        }
    }

    private static String reg(final Object index) {
        if (index instanceof Integer) {
            return REGISTER_NAME[(int) index];
        } else {
            return index.toString();
        }
    }

    private static String csr2n(final int csr) {
        if (CSR_NAME.containsKey(csr)) {
            return CSR_NAME.get(csr);
        }

        if (csr >= 0xC03 && csr <= 0xC1F) {
            return "hpmcounter" + (3 + (csr - 0xC03));
        }

        if (csr >= 0xC83 && csr <= 0xC9F) {
            return "hpmcounter" + (3 + (csr - 0xC03)) + "h";
        }

        if (csr >= 0x3A0 && csr <= 0x3AF) {
            return "pmpcfg" + (csr - 0x3A0);
        }
        if (csr >= 0x3B0 && csr <= 0x3EF) {
            return "pmpaddr" + (csr - 0x3B0);
        }

        if (csr >= 0xB03 && csr <= 0xB1F) {
            return "mhpmcounter" + (3 + (csr - 0xB03));
        }
        if (csr >= 0xB83 && csr <= 0xB9F) {
            return "mhpmcounter" + (3 + (csr - 0xB83)) + "h";
        }

        if (csr >= 0x323 && csr <= 0x33F) {
            return "mhpmevent" + (3 + (csr - 0x323));
        }

        return String.valueOf(csr);
    }

    private static int argIndex(final String argName) {
        if (ARGUMENT_INDEX.containsKey(argName)) {
            return ARGUMENT_INDEX.getInt(argName);
        } else {
            return Integer.MAX_VALUE;
        }
    }

    private static final class FormatPattern {
        private static final Pattern ARG_PATTERN = Pattern.compile("(%[a-zA-Z0-9]+)");

        private final String instName;
        private final String alias;
        private final String argFormat;
        private final ArrayList<String> args;
        private final ArrayList<ArgFilter> argFilters;

        public FormatPattern(final String instName, final String alias, final String argFormat) {
            this.instName = instName;
            this.alias = alias;
            this.argFormat = argFormat;
            args = new ArrayList<>();
            final Matcher matcher = ARG_PATTERN.matcher(argFormat);
            while (matcher.find()) {
                args.add(matcher.group().substring(1)); // Skip %
            }
            this.argFilters = new ArrayList<>();
        }

        public FormatPattern(final String instName, final String alias) {
            this(instName, alias, "");
        }

        public FormatPattern withFilter(final String argName, final int argValue) {
            argFilters.add(new ArgFilter(argName, argValue));
            return this;
        }

        public boolean matches(final int instruction, final InstructionDeclaration declaration) {
            if (!Objects.equals(declaration.displayName, instName) &&
                !Objects.equals(declaration.name, instName)) {
                return false;
            }

            for (final String argName : args) {
                if (!declaration.arguments.containsKey(argName)) {
                    return false;
                }
            }

            for (final ArgFilter filter : argFilters) {
                if (!filter.matches(instruction, declaration)) {
                    return false;
                }
            }

            return true;
        }

        public void format(final int instruction, final InstructionDeclaration declaration, final StringBuffer sb) {
            sb.append(StringUtils.rightPad(alias, INST_WIDTH));

            final Matcher matcher = ARG_PATTERN.matcher(argFormat);
            while (matcher.find()) {
                final String argName = matcher.group().substring(1);
                matcher.appendReplacement(sb, arg2s(instruction, declaration, argName));
            }
            matcher.appendTail(sb);
        }

        private static final class ArgFilter {
            public final String argName;
            public final int argValue;

            private ArgFilter(final String argName, final int argValue) {
                this.argName = argName;
                this.argValue = argValue;
            }

            public boolean matches(final int instruction, final InstructionDeclaration declaration) {
                if (!declaration.arguments.containsKey(argName)) {
                    return false;
                }

                return declaration.arguments.get(argName).get(instruction) == argValue;
            }
        }
    }
}
