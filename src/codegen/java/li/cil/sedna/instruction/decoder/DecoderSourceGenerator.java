package li.cil.sedna.instruction.decoder;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDefinition;
import li.cil.sedna.instruction.InstructionFieldMapping;
import li.cil.sedna.instruction.InstructionType;
import li.cil.sedna.instruction.argument.ConstantInstructionArgument;
import li.cil.sedna.instruction.argument.FieldInstructionArgument;
import li.cil.sedna.instruction.argument.InstructionArgument;
import li.cil.sedna.instruction.argument.ProgramCounterInstructionArgument;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeBranchNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeSwitchNode;
import li.cil.sedna.utils.BitUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emits a decoder as Java source, given a decoder tree.
 * <p>
 * Some design decisions leading to the current implementation:
 * <ul>
 *     <li>Switch keys are compacted so that the cases form a dense range, which is what gets a
 *     {@code tableswitch} out of javac rather than a chain of comparisons.</li>
 *     <li>Operand extraction shared by enough instructions is hoisted into locals once, rather
 *     than repeated in every leaf.</li>
 *     <li>Sub-trees are split into separate methods, both to stay under the 64KiB limit on method
 *     bytecode and to keep any one method small enough for the JIT to compile well. Those methods
 *     report back through an int on what to do (continue, exit [optional pc bump], jump).</li>
 * </ul>
 */
public final class DecoderSourceGenerator {
    private enum ContextType {
        TOP_LEVEL,
        VOID_METHOD,
        CONDITIONAL_METHOD,
    }

    private static final int RETURN_CONTINUE = 0; // update pc then keep going
    private static final int RETURN_EXIT_INC_PC = 1; // update pc then exit the decoder loop
    private static final int RETURN_EXIT = 2; // exit the decoder loop
    private static final int RETURN_JUMP = 3; // pc was written by a non-branch (trap): exit the decoder loop
    private static final int RETURN_JUMP_BRANCH = 4; // pc was written by a pure branch; may continue in-page

    private static final float HOIST_THRESHOLD = 0.99f;

    private final AbstractDecoderTreeNode decoderTree;
    private final Function<InstructionDeclaration, InstructionDefinition> definitionProvider;
    private final Class<? extends Throwable> illegalInstructionExceptionClass;
    private final String methodPrefix;
    private final SourceBuilder out;
    private final List<String> groupMethods = new ArrayList<>();

    private int groupMethodIndex;

    public DecoderSourceGenerator(final AbstractDecoderTreeNode decoderTree,
                                  final Function<InstructionDeclaration, InstructionDefinition> definitionProvider,
                                  final Class<? extends Throwable> illegalInstructionExceptionClass,
                                  final String methodPrefix,
                                  final SourceBuilder out) {
        this.decoderTree = decoderTree;
        this.definitionProvider = definitionProvider;
        this.illegalInstructionExceptionClass = illegalInstructionExceptionClass;
        this.methodPrefix = methodPrefix;
        this.out = out;
    }

    public void generate() {
        final Context context = new Context(ContextType.TOP_LEVEL, 0, "inst", "pc", new Object2ObjectArrayMap<>(), out);
        decoderTree.accept(new NodeVisitor(context, null));
    }

    public List<String> getGroupMethods() {
        return groupMethods;
    }

    // ------------------------------------------------------------- //
    // Emission context

    private final class Context {
        final ContextType type;
        final int processedMask;
        final String instExpr;
        final String pcExpr;
        final Object2ObjectArrayMap<FieldInstructionArgument, String> locals;
        final SourceBuilder out;

        Context(final ContextType type, final int processedMask, final String instExpr, final String pcExpr,
                final Object2ObjectArrayMap<FieldInstructionArgument, String> locals, final SourceBuilder out) {
            this.type = type;
            this.processedMask = processedMask;
            this.instExpr = instExpr;
            this.pcExpr = pcExpr;
            this.locals = locals;
            this.out = out;
        }

        Context withProcessed(final int mask) {
            return new Context(type, processedMask | mask, instExpr, pcExpr, locals, out);
        }

        Context withOutput(final SourceBuilder other) {
            return new Context(type, processedMask, instExpr, pcExpr, locals, other);
        }

        void emitContinue() {
            switch (type) {
                // Not "continue": the fetch of the next instruction sits after the decode section,
                // and has to run. This is what the bytecode generator's continueLabel, placed after
                // the decode, achieves.
                case TOP_LEVEL -> out.line("break decode;");
                case VOID_METHOD -> out.line("return;");
                case CONDITIONAL_METHOD -> out.line("return " + RETURN_CONTINUE + ";");
            }
        }

        void emitIllegalInstruction() {
            out.line("throw illegalInstruction();");
        }

        void emitIncrementPC(final int size) {
            if (type == ContextType.TOP_LEVEL) {
                out.line("pc += " + size + ";");
                out.line("instOffset += " + size + ";");
            }
        }

        void emitSavePC() {
            out.line("this.pc = pc;");
        }

        void emitJumpHandler(final boolean mayContinue) {
            out.line("final long jumpTarget = this.pc;");
            out.line("if (Long.compareUnsigned(pc, jumpTarget) >= 0) {");
            if (mayContinue) {
                out.indent(() -> {
                    out.line("if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {");
                    out.indent(() -> out.line("return;"));
                    out.line("}");
                });
            } else {
                out.indent(() -> out.line("return;"));
            }
            out.line("}");
            out.line("final long jumpDelta = jumpTarget - pc;");
            out.line("pc = jumpTarget;");
            out.line("if ((long) (int) jumpDelta != jumpDelta) {");
            out.indent(() -> out.line("return;"));
            out.line("}");
            out.line("instOffset += (int) jumpDelta;");
            out.line("break decode;");
        }
    }

    // ------------------------------------------------------------- //
    // Expressions

    /**
     * The value of an operand, as an expression over the current instruction word.
     */
    private String fieldExpression(final FieldInstructionArgument argument, final String instExpr) {
        final StringBuilder sb = new StringBuilder();
        for (final InstructionFieldMapping mapping : argument.mappings) {
            final String shifted;
            if (mapping.dstLSB >= mapping.srcLSB) {
                final int shift = mapping.dstLSB - mapping.srcLSB;
                shifted = shift == 0 ? instExpr : "(" + instExpr + " << " + shift + ")";
            } else {
                shifted = "(" + instExpr + " >>> " + (mapping.srcLSB - mapping.dstLSB) + ")";
            }

            final int mask = ((1 << (mapping.srcMSB - mapping.srcLSB + 1)) - 1) << mapping.dstLSB;
            String term = "(" + shifted + " & " + hex(mask) + ")";

            if (mapping.signExtend) {
                term = "BitUtils.extendSign(" + term + ", " + (mapping.dstLSB + (mapping.srcMSB - mapping.srcLSB) + 1) + ")";
            }

            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(term);
        }

        String expression = sb.length() == 0 ? "0" : sb.toString();

        switch (argument.postprocessor) {
            case NONE -> {
            }
            case ADD_8 -> expression = "(" + expression + ") + 8";
            default -> throw new IllegalArgumentException();
        }

        return expression;
    }

    private String argumentExpression(final Context context, final FieldInstructionArgument argument) {
        final String local = context.locals.get(argument);
        return local != null ? local : fieldExpression(argument, context.instExpr);
    }

    private static String hex(final int value) {
        return "0x" + Integer.toHexString(value);
    }

    // ------------------------------------------------------------- //
    // Tree walking

    private final class NodeVisitor implements DecoderTreeVisitor {
        private final Context context;
        private final Runnable onEnd;

        private SourceBuilder splitBody;
        private int splitSlot;

        NodeVisitor(final Context context, final Runnable onEnd) {
            this.context = context;
            this.onEnd = onEnd;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            return new SwitchVisitor(maybeSplitIntoMethod(node));
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            return new BranchVisitor(maybeSplitIntoMethod(node));
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(context);
        }

        @Override
        public void visitEnd() {
            if (splitBody != null) {
                splitBody.pop();
                splitBody.line("}");
                groupMethods.set(splitSlot, splitBody.toString());
                splitBody = null;
            }

            if (onEnd != null) {
                onEnd.run();
            }
        }

        private Context maybeSplitIntoMethod(final AbstractDecoderTreeNode node) {
            final List<InstructionDeclaration> instructions = node.getInstructions().toList();
            if (instructions.size() == 1) {
                return context;
            }

            final OptionalInt commonInstructionSize = commonInstructionSize(node);
            if (commonInstructionSize.isEmpty()) {
                return context;
            }

            // Operands the sub-tree needs that we already have in locals get passed along, rather
            // than being extracted a second time inside the method. Sorted by local name because
            // the argument set does not have a stable order (see hoistLocals).
            final List<FieldInstructionArgument> parameters = new ArrayList<>(node.getArguments().arguments.keySet());
            parameters.retainAll(context.locals.keySet());
            parameters.sort(Comparator.comparing(context.locals::get));

            final List<InstructionDefinition> definitions = instructions.stream()
                    .map(definitionProvider)
                    .filter(Objects::nonNull)
                    .toList();
            final boolean containsReturns = definitions.stream().anyMatch(d -> d.writesPC || d.returnsBoolean);

            final String methodName = methodPrefix + "$instructionGroup" + (groupMethodIndex++);

            final StringBuilder signature = new StringBuilder();
            signature.append("private ").append(containsReturns ? "int" : "void").append(' ')
                    .append(methodName).append("(final int inst, final long pc");
            final Object2ObjectArrayMap<FieldInstructionArgument, String> localsInMethod = new Object2ObjectArrayMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                final String name = "arg" + i;
                localsInMethod.put(parameters.get(i), name);
                signature.append(", final int ").append(name);
            }
            signature.append(')');

            final LinkedHashSet<String> exceptions = new LinkedHashSet<>();
            exceptions.add(illegalInstructionExceptionClass.getName());
            definitions.stream()
                    .map(d -> d.thrownExceptions)
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .map(DecoderSourceGenerator::binaryName)
                    .forEach(exceptions::add);
            signature.append(" throws ").append(String.join(", ", exceptions));

            final StringBuilder call = new StringBuilder(methodName).append('(').append(context.instExpr)
                    .append(", ").append(context.pcExpr);
            for (final FieldInstructionArgument parameter : parameters) {
                call.append(", ").append(context.locals.get(parameter));
            }
            call.append(')');

            if (containsReturns) {
                emitGroupCallWithReturns(call.toString(), commonInstructionSize.getAsInt());
            } else {
                context.out.line(call + ";");
                context.emitIncrementPC(commonInstructionSize.getAsInt());
                context.emitContinue();
            }

            final SourceBuilder body = new SourceBuilder();
            body.line(signature.toString() + " {");
            body.push();

            final Context methodContext = new Context(
                    containsReturns ? ContextType.CONDITIONAL_METHOD : ContextType.VOID_METHOD,
                    context.processedMask, "inst", "pc", localsInMethod, body);

            groupMethods.add(null);
            splitSlot = groupMethods.size() - 1;
            splitBody = body;

            return methodContext;
        }

        private void emitGroupCallWithReturns(final String call, final int instructionSize) {
            final SourceBuilder out = context.out;
            switch (context.type) {
                case TOP_LEVEL -> {
                    out.line("switch (" + call + ") {");
                    out.indent(() -> {
                        out.line("case " + RETURN_CONTINUE + " -> {");
                        out.indent(() -> {
                            context.emitIncrementPC(instructionSize);
                            out.line("break decode;");
                        });
                        out.line("}");
                        out.line("case " + RETURN_EXIT_INC_PC + " -> {");
                        out.indent(() -> {
                            context.emitIncrementPC(instructionSize);
                            context.emitSavePC();
                            out.line("return;");
                        });
                        out.line("}");
                        out.line("case " + RETURN_EXIT + " -> {");
                        out.indent(() -> out.line("return;"));
                        out.line("}");
                        out.line("case " + RETURN_JUMP + " -> {");
                        out.indent(() -> context.emitJumpHandler(false));
                        out.line("}");
                        out.line("case " + RETURN_JUMP_BRANCH + " -> {");
                        out.indent(() -> context.emitJumpHandler(true));
                        out.line("}");
                        out.line("default -> throw illegalInstruction();");
                    });
                    out.line("}");
                }
                // Nothing here can touch the program counter either, so keep bubbling the code up.
                case CONDITIONAL_METHOD -> out.line("return " + call + ";");
                default -> throw new IllegalStateException();
            }
        }

        private OptionalInt commonInstructionSize(final AbstractDecoderTreeNode node) {
            final List<Integer> sizes = node.getInstructions().map(i -> i.size).distinct().toList();
            return sizes.size() == 1 ? OptionalInt.of(sizes.get(0)) : OptionalInt.empty();
        }
    }

    // ------------------------------------------------------------- //
    // Nodes

    private abstract class HoistingVisitor {
        protected final Context context;
        private final List<FieldInstructionArgument> hoisted = new ArrayList<>();

        protected HoistingVisitor(final Context context) {
            this.context = context;
        }

        protected void hoistLocals(final DecoderTreeNodeArguments arguments) {
            final int threshold = Math.max(2, (int) (arguments.totalLeafCount * HOIST_THRESHOLD));
            // Sort for stable, deterministic output (e.g. for change check in test).
            arguments.arguments.entrySet().stream()
                    .filter(e -> e.getValue().count >= threshold && !context.locals.containsKey(e.getKey()))
                    .sorted(Comparator.comparing(e -> localName(e.getValue())))
                    .forEach(e -> {
                        final FieldInstructionArgument argument = e.getKey();
                        final String name = localName(e.getValue());
                        hoisted.add(argument);
                        context.locals.put(argument, name);
                        context.out.line("final int " + name + " = " + fieldExpression(argument, context.instExpr) + ";");
                    });
        }

        private String localName(final DecoderTreeNodeArguments.Entry entry) {
            return entry.names.stream().sorted().collect(Collectors.joining("_"));
        }

        protected void dropLocals() {
            for (final FieldInstructionArgument argument : hoisted) {
                context.locals.remove(argument);
            }
        }
    }

    private final class SwitchVisitor extends HoistingVisitor implements DecoderTreeSwitchVisitor {
        private int switchMask;

        SwitchVisitor(final Context context) {
            super(context);
        }

        @Override
        public void visit(final int mask, final int[] patterns, final DecoderTreeNodeArguments arguments) {
            hoistLocals(arguments);

            final int caseCount = patterns.length;
            switchMask = mask & ~context.processedMask;

            final int unprocessedMask = mask & ~context.processedMask;
            final ArrayList<MaskField> maskFields = MaskField.create(unprocessedMask);

            final ArrayList<MaskField> commonFields = new ArrayList<>();
            for (int i = maskFields.size() - 1; i >= 0; i--) {
                final int fieldMask = maskFields.get(i).asMask();
                final int pattern = patterns[0] & fieldMask;

                boolean allMatch = true;
                for (int j = 1; j < caseCount; j++) {
                    if ((patterns[j] & fieldMask) != pattern) {
                        allMatch = false;
                        break;
                    }
                }

                if (allMatch) {
                    commonFields.add(maskFields.remove(i));
                }
            }

            if (maskFields.isEmpty()) {
                throw new IllegalStateException(String.format("All cases in a switch node have the same patterns: [%s]",
                        commonFields.stream().map(f -> Integer.toBinaryString(patterns[0] & f.asMask())).collect(Collectors.joining(", "))));
            }

            if (!commonFields.isEmpty()) {
                int commonMask = 0;
                for (final MaskField field : commonFields) {
                    commonMask |= field.asMask();
                }
                final int commonPattern = patterns[0] & commonMask;
                context.out.line("if ((" + context.instExpr + " & " + hex(commonMask) + ") != " + hex(commonPattern) + ") {");
                context.out.indent(context::emitIllegalInstruction);
                context.out.line("}");
            }

            // Compact the selecting fields down to adjacent bits, so the case values form as dense a
            // range as possible and javac emits a tableswitch rather than a comparison chain.
            final int[] tablePatterns = new int[caseCount];
            for (int i = 0; i < caseCount; i++) {
                int tablePattern = 0;
                int offset = 0;
                for (final MaskField field : maskFields) {
                    tablePattern |= ((patterns[i] & field.asMask()) >>> field.srcLSB) << offset;
                    offset += field.srcMSB - field.srcLSB + 1;
                }
                tablePatterns[i] = tablePattern;
            }

            if (preferTableSwitch(maskFields, tablePatterns)) {
                context.out.line("switch (" + compactedKeyExpression(maskFields, unprocessedMask) + ") {");
                caseValues = tablePatterns;
            } else {
                context.out.line("switch (" + context.instExpr + " & " + hex(mask) + ") {");
                final int[] rawPatterns = new int[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    rawPatterns[i] = patterns[i] & mask;
                }
                caseValues = rawPatterns;
            }
            context.out.push();
        }

        private int[] caseValues;

        private boolean preferTableSwitch(final List<MaskField> maskFields, final int[] tablePatterns) {
            final int[] sorted = tablePatterns.clone();
            Arrays.sort(sorted);

            final int tableSize = sorted[sorted.length - 1] - sorted[0] + 1;
            final int tableSpaceCost = 4 + tableSize;
            final int tableTimeCost = 3;
            final int maskingCost;
            if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                maskingCost = 0;
            } else {
                maskingCost = 1 + ((maskFields.get(0).srcLSB == 0) ? 4 : 6) + (maskFields.size() - 1) * (3 + 2 + 2 + 1) - 3;
            }
            final int lookupSpaceCost = 3 + 2 * tablePatterns.length;
            final int lookupTimeCost = tablePatterns.length;

            return maskingCost + tableSpaceCost + 3 * tableTimeCost <= lookupSpaceCost + 3 * lookupTimeCost;
        }

        private String compactedKeyExpression(final List<MaskField> maskFields, final int unprocessedMask) {
            if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                return context.instExpr + " & " + hex(unprocessedMask);
            }

            final StringBuilder sb = new StringBuilder();
            int offset = 0;
            for (final MaskField field : maskFields) {
                String term = "(" + context.instExpr + " & " + hex(field.asMask()) + ")";
                if (field.srcLSB > 0) {
                    term = "(" + term + " >>> " + field.srcLSB + ")";
                    if (offset > 0) {
                        term = "(" + term + " << " + offset + ")";
                    }
                }
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(term);
                offset += field.srcMSB - field.srcLSB + 1;
            }
            return sb.toString();
        }

        @Override
        public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
            context.out.line("case " + caseValues[index] + ": {");
            context.out.push();
            return new NodeVisitor(context.withProcessed(switchMask), () -> {
                context.out.pop();
                context.out.line("}");
            });
        }

        @Override
        public void visitEnd() {
            context.out.line("default:");
            context.out.indent(context::emitIllegalInstruction);
            context.out.pop();
            context.out.line("}");

            dropLocals();
        }
    }

    private final class BranchVisitor extends HoistingVisitor implements DecoderTreeBranchVisitor {
        private boolean unconditionalCaseEmitted;

        BranchVisitor(final Context context) {
            super(context);
        }

        @Override
        public void visit(final int count, final DecoderTreeNodeArguments arguments) {
            hoistLocals(arguments);
        }

        @Override
        public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
            final int remainingMask = mask & ~context.processedMask;

            if (unconditionalCaseEmitted) {
                return new NodeVisitor(context.withOutput(new SourceBuilder()), null);
            }

            if (remainingMask == 0) {
                unconditionalCaseEmitted = true;
                return new NodeVisitor(context.withProcessed(remainingMask), null);
            }

            context.out.line("if ((" + context.instExpr + " & " + hex(remainingMask) + ") == "
                    + hex(pattern & ~context.processedMask) + ") {");
            context.out.push();
            return new NodeVisitor(context.withProcessed(remainingMask), () -> {
                context.out.pop();
                context.out.line("}");
            });
        }

        @Override
        public void visitEnd() {
            if (!unconditionalCaseEmitted) {
                context.emitIllegalInstruction();
            }

            dropLocals();
        }
    }

    private final class LeafVisitor implements DecoderTreeLeafVisitor {
        private final Context context;

        LeafVisitor(final Context context) {
            this.context = context;
        }

        @Override
        public void visitInstruction(final InstructionDeclaration declaration) {
            if (declaration.type == InstructionType.ILLEGAL) {
                context.emitIllegalInstruction();
                return;
            }

            if (declaration.type == InstructionType.NOP) {
                context.emitIncrementPC(declaration.size);
                context.emitContinue();
                return;
            }

            final InstructionDefinition definition = definitionProvider.apply(declaration);
            if (definition == null) {
                context.emitIllegalInstruction();
                return;
            }

            emitInstruction(declaration, definition);
        }

        private void emitInstruction(final InstructionDeclaration declaration, final InstructionDefinition definition) {
            final List<String> arguments = new ArrayList<>();
            for (final InstructionArgument argument : definition.parameters) {
                if (argument instanceof final ConstantInstructionArgument constant) {
                    arguments.add(Integer.toString(constant.value));
                } else if (argument instanceof ProgramCounterInstructionArgument) {
                    arguments.add(context.pcExpr);
                } else if (argument instanceof final FieldInstructionArgument field) {
                    arguments.add(argumentExpression(context, field));
                } else {
                    throw new IllegalArgumentException();
                }
            }

            final String call = definition.methodName + "(" + String.join(", ", arguments) + ")";

            if (definition.returnsBoolean) {
                context.out.line("if (" + call + ") {");
                context.out.indent(() -> {
                    switch (context.type) {
                        case TOP_LEVEL -> {
                            if (definition.isBranch) {
                                context.emitJumpHandler(true);
                            } else {
                                if (!definition.writesPC) {
                                    context.emitIncrementPC(declaration.size);
                                    context.emitSavePC();
                                }
                                context.out.line("return;");
                            }
                        }
                        case CONDITIONAL_METHOD -> {
                            if (definition.isBranch) {
                                context.out.line("return " + RETURN_JUMP_BRANCH + ";");
                            } else {
                                context.out.line("return " + (definition.writesPC ? RETURN_EXIT : RETURN_EXIT_INC_PC) + ";");
                            }
                        }
                        default -> throw new IllegalStateException();
                    }
                });
                context.out.line("}");
                context.emitIncrementPC(declaration.size);
                context.emitContinue();
            } else if (definition.writesPC) {
                context.out.line(call + ";");
                switch (context.type) {
                    case TOP_LEVEL -> context.emitJumpHandler(definition.isBranch);
                    case CONDITIONAL_METHOD ->
                            context.out.line("return " + (definition.isBranch ? RETURN_JUMP_BRANCH : RETURN_JUMP) + ";");
                    default -> throw new IllegalStateException();
                }
            } else {
                context.out.line(call + ";");
                context.emitIncrementPC(declaration.size);
                context.emitContinue();
            }
        }

        @Override
        public void visitEnd() {
        }
    }

    private static String binaryName(final String internalName) {
        return internalName.replace('/', '.');
    }

    // ------------------------------------------------------------- //
    // Support types

    private record MaskField(int srcMSB, int srcLSB) {
        static ArrayList<MaskField> create(int mask) {
            final ArrayList<MaskField> maskFields = new ArrayList<>();
            int offset = 0;
            while (mask != 0) {
                final int lsb = Integer.numberOfTrailingZeros(mask);
                mask = mask >>> lsb;
                int msb = lsb - 1;
                while ((mask & 1) != 0) {
                    msb++;
                    mask = mask >>> 1;
                }
                maskFields.add(new MaskField(msb + offset, lsb + offset));
                offset += msb + 1;
            }
            return maskFields;
        }

        int asMask() {
            return (int) BitUtils.maskFromRange(srcLSB, srcMSB);
        }
    }
}
