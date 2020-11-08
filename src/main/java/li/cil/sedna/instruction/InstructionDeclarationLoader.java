package li.cil.sedna.instruction;

import li.cil.sedna.instruction.argument.ConstantInstructionArgument;
import li.cil.sedna.instruction.argument.FieldInstructionArgument;
import li.cil.sedna.instruction.argument.InstructionArgument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public final class InstructionDeclarationLoader {
    private static final String FIELD_KEYWORD = "field";

    public static ArrayList<InstructionDeclaration> load(final InputStream stream) throws IOException {
        final ParserContext context = new ParserContext();
        parseFile(new BufferedReader(new InputStreamReader(stream)), context);
        validateDeclarations(context.instructions);
        return context.instructions;
    }

    private static void validateDeclarations(final ArrayList<InstructionDeclaration> declarations) {
        // All instructions must have some intersection in their pattern masks, and where
        // they do intersect they have to be different. Otherwise we have some ambiguous
        // instruction definitions.
        for (int i = 0; i < declarations.size(); i++) {
            final InstructionDeclaration i1 = declarations.get(i);
            for (int j = i + 1; j < declarations.size(); j++) {
                final InstructionDeclaration i2 = declarations.get(j);
                final int intersectMask = i1.patternMask & i2.patternMask;
                if (intersectMask == 0) {
                    throw new IllegalStateException(String.format("Instruction declarations [%s] (line %d) and [%s] (line %d) have distinct pattern masks, making them ambiguous.", i1.displayName, i1.lineNumber, i2.displayName, i2.lineNumber));
                }
                if ((i1.patternMask & intersectMask) == i1.patternMask &&
                    (i2.patternMask & intersectMask) == i2.patternMask &&
                    (i1.pattern & intersectMask) == (i2.pattern & intersectMask)) {
                    throw new IllegalStateException(String.format("Instruction declarations [%s] (line %d) and [%s] (line %d) have ambiguous patterns.", i1.displayName, i1.lineNumber, i2.displayName, i2.lineNumber));
                }
            }
        }
    }

    private static void parseFile(final BufferedReader reader, final ParserContext context) throws IOException {
        while ((context.line = reader.readLine()) != null) {
            try {
                parseComments(context);
                parseTokens(context);
                parseLine(context);
            } catch (final IllegalArgumentException e) {
                throw new IOException(String.format("Failed parsing line [%d].", context.lineNumber), e);
            }
            context.lineNumber++;
        }
    }

    private static void parseComments(final ParserContext context) {
        context.line = context.line.split("#", 2)[0].trim();
    }

    private static void parseTokens(final ParserContext context) {
        context.tokens.clear();
        if (!context.line.isEmpty()) {
            context.tokens.addAll(Arrays.asList(context.line.split("\\s+")));
        }
    }

    private static void parseLine(final ParserContext context) {
        if (context.tokens.isEmpty()) {
            return;
        }

        final String keyword = context.tokens.remove(0);

        if (FIELD_KEYWORD.equals(keyword)) {
            final Field field = parseField(context);
            context.fields.put(field.name, field);
        } else if (InstructionType.BY_KEYWORD.containsKey(keyword)) {
            final InstructionType type = InstructionType.BY_KEYWORD.get(keyword);
            final InstructionDeclaration declaration = parseInstruction(context, type);
            context.instructions.add(declaration);
        } else {
            throw new IllegalArgumentException(String.format("Invalid keyword [%s].", keyword));
        }
    }

    private static InstructionDeclaration parseInstruction(final ParserContext context, final InstructionType type) {
        final String name;
        switch (type) {
            case REGULAR:
                name = context.tokens.remove(0);
                break;
            case NOP:
                name = "NOP";
                break;
            case ILLEGAL:
                name = "ILLEGAL";
                break;
            default:
                throw new IllegalArgumentException();
        }

        final String displayName;
        if ("|".equals(context.tokens.get(0))) {
            displayName = name;
        } else {
            displayName = context.tokens.remove(0);
        }

        if (!"|".equals(context.tokens.get(0))) {
            throw new IllegalArgumentException(String.format("Unexpected token [%s].", context.tokens.get(0)));
        }
        context.tokens.remove(0);

        int pattern = 0;
        int patternMask = 0;
        int unusedBits = 0;
        int bitIndex = 31;
        while (!context.tokens.isEmpty() && !"|".equals(context.tokens.get(0))) {
            final String token = context.tokens.remove(0);
            for (int j = 0; j < token.length(); j++) {
                if (bitIndex < 0) {
                    throw new IllegalArgumentException("Instruction bit pattern too long (>32 bits).");
                }

                switch (token.charAt(j)) {
                    case '0': {
                        patternMask |= (1 << bitIndex);
                        break;
                    }
                    case '1': {
                        patternMask |= (1 << bitIndex);
                        pattern |= (1 << bitIndex);
                        break;
                    }
                    case '*': {
                        unusedBits |= (1 << bitIndex);
                        break;
                    }
                    case '.': {
                        // Field bit, must be consumed by an argument.
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException(String.format("Unexpected character [%s] in instruction bit pattern.", token.charAt(j)));
                    }
                }
                bitIndex--;
            }
        }

        final boolean isCompressedInstruction = bitIndex == 15;
        if (isCompressedInstruction) {
            pattern = pattern >>> 16;
            patternMask = patternMask >>> 16;
            unusedBits = unusedBits >>> 16;
        }

        final LinkedHashMap<String, InstructionArgument> arguments = new LinkedHashMap<>();
        int argumentBits = 0;
        if (!context.tokens.isEmpty()) {
            if (!"|".equals(context.tokens.get(0))) {
                throw new IllegalArgumentException(String.format("Unexpected token [%s].", context.tokens.get(0)));
            }
            context.tokens.remove(0);

            while (!context.tokens.isEmpty()) {
                if (type == InstructionType.NOP || type == InstructionType.ILLEGAL) {
                    throw new IllegalArgumentException(String.format("Unexpected token [%s].", context.tokens.get(0)));
                }

                final ParsedArgument argument = parseArgument(context);
                argumentBits |= argument.bitmask;
                for (final String argName : argument.names) {
                    arguments.put(argName, argument.value);
                }
            }
        }

        if ((argumentBits & patternMask) != 0) {
            throw new IllegalArgumentException("Argument bits intersect pattern bits.");
        }

        final int usedBits = patternMask | unusedBits | argumentBits;
        if (isCompressedInstruction ? usedBits != 0x0000FFFF : usedBits != 0xFFFFFFFF) {
            throw new IllegalArgumentException("Not all instruction bits have a defined use.");
        }

        return new InstructionDeclaration(type, isCompressedInstruction ? 2 : 4, name, displayName, context.lineNumber, pattern, patternMask, unusedBits, arguments);
    }

    private static ParsedArgument parseArgument(final ParserContext context) {
        final String token = context.tokens.remove(0);
        final String[] argsAndFieldNameOrConst = token.split("=");

        final ParsedArgument result = new ParsedArgument();
        result.names = new String[Math.max(1, argsAndFieldNameOrConst.length - 1)];
        System.arraycopy(argsAndFieldNameOrConst, 0, result.names, 0, Math.max(1, argsAndFieldNameOrConst.length - 1));

        final String fieldNameOrConst = argsAndFieldNameOrConst[argsAndFieldNameOrConst.length - 1];
        if (argsAndFieldNameOrConst.length > 1) {
            try {
                final int constValue = Integer.parseInt(fieldNameOrConst);
                result.value = new ConstantInstructionArgument(constValue);
                return result;
            } catch (final NumberFormatException ignored) {
            }
        }

        final Field field = context.fields.get(fieldNameOrConst);
        if (field == null) {
            throw new IllegalArgumentException(String.format("Reference to unknown field [%s].", fieldNameOrConst));
        }

        FieldInstructionArgument argument = new FieldInstructionArgument(field.mappings, field.postprocessor);

        // Re-use existing field arguments to make it easier to group them during code-gen.
        boolean isNewArgument = true;
        for (final FieldInstructionArgument existingArgument : context.distinctFieldArguments) {
            if (existingArgument.equals(argument)) {
                argument = existingArgument;
                isNewArgument = false;
                break;
            }
        }
        if (isNewArgument) {
            context.distinctFieldArguments.add(argument);
        }

        result.value = argument;

        int mappingsBits = 0;
        for (final InstructionFieldMapping mapping : field.mappings) {
            mappingsBits |= ((2 << mapping.srcMSB) - 1) & ~((1 << mapping.srcLSB) - 1);
        }
        result.bitmask = mappingsBits;

        return result;
    }

    private static Field parseField(final ParserContext context) {
        final String name = context.tokens.remove(0);
        final ArrayList<InstructionFieldMapping> mappings = new ArrayList<>(context.tokens.size());
        while (!context.tokens.isEmpty() && !"|".equals(context.tokens.get(0))) {
            InstructionFieldMapping mapping = parseFieldMapping(context);

            // Re-use existing identical mapping if possible. This makes it easier to group
            // them during code-gen, for example.
            boolean isNewMapping = true;
            for (final InstructionFieldMapping existingMapping : context.distinctFieldMappings) {
                if (existingMapping.equals(mapping)) {
                    mapping = existingMapping;
                    isNewMapping = false;
                    break;
                }
            }
            if (isNewMapping) {
                context.distinctFieldMappings.add(mapping);
            }

            mappings.add(mapping);
        }

        final FieldPostprocessor postprocessor;
        if (!context.tokens.isEmpty()) {
            if (!"|".equals(context.tokens.get(0))) {
                throw new IllegalArgumentException(String.format("Unexpected token [%s].", context.tokens.get(0)));
            }
            context.tokens.remove(0);

            final String postprocessorName = context.tokens.remove(0).toUpperCase(Locale.ENGLISH);
            postprocessor = FieldPostprocessor.valueOf(postprocessorName);

            if (!context.tokens.isEmpty()) {
                throw new IllegalArgumentException(String.format("Unexpected token [%s].", context.tokens.get(0)));
            }
        } else {
            postprocessor = FieldPostprocessor.NONE;
        }

        return new Field(name, mappings, postprocessor);
    }

    private static InstructionFieldMapping parseFieldMapping(final ParserContext context) {
        final String spec = context.tokens.remove(0);
        final int srcLSB, srcMSB, dstLSB;
        final boolean signExtend;
        final String[] srcRangeAndDstLSB = spec.split("@", 2);
        try {
            dstLSB = srcRangeAndDstLSB.length > 1 ? Integer.parseInt(srcRangeAndDstLSB[1]) : 0;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Failed parsing destination least significant bit in field spec [%s].", spec));
        }
        final String[] srcMSBAndLSB = srcRangeAndDstLSB[0].split(":", 2);
        if (srcMSBAndLSB[0].charAt(0) == 's') {
            try {
                srcMSB = Integer.parseInt(srcMSBAndLSB[0].substring(1));
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Failed parsing source most significant bit in field spec [%s].", spec));
            }
            signExtend = true;
        } else {
            try {
                srcMSB = Integer.parseInt(srcMSBAndLSB[0]);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Failed parsing source most significant bit in field spec [%s].", spec));
            }
            signExtend = false;
        }
        if (srcMSBAndLSB.length > 1) {
            try {
                srcLSB = Integer.parseInt(srcMSBAndLSB[1]);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Failed parsing source least significant bit in field spec [%s].", spec));
            }
        } else {
            srcLSB = srcMSB;
        }
        return new InstructionFieldMapping(srcMSB, srcLSB, dstLSB, signExtend);
    }

    private static final class ParsedArgument {
        public String[] names;
        public InstructionArgument value;
        public int bitmask;
    }

    private static final class Field {
        public final String name;
        public final ArrayList<InstructionFieldMapping> mappings;
        private final FieldPostprocessor postprocessor;

        private Field(final String name, final ArrayList<InstructionFieldMapping> mappings, final FieldPostprocessor postprocessor) {
            this.name = name;
            this.mappings = mappings;
            this.postprocessor = postprocessor;
        }
    }

    private static final class ParserContext {
        public int lineNumber = 1;
        public String line;
        public ArrayList<String> tokens = new ArrayList<>();

        public final ArrayList<InstructionFieldMapping> distinctFieldMappings = new ArrayList<>();
        public final ArrayList<FieldInstructionArgument> distinctFieldArguments = new ArrayList<>();

        public final HashMap<String, Field> fields = new HashMap<>();
        public final ArrayList<InstructionDeclaration> instructions = new ArrayList<>();
    }
}
