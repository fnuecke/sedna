package li.cil.sedna.instruction.decoder;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
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
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class visitor can be used to generate code based on a decoder tree.
 * <p>
 * Some very strict requirements must be fulfilled for this class visitor to emit valid and
 * correct code:
 * <ul>
 *     <li>The class being visited <em>must</em> have a method named exactly as specified in the {@code decoderMethod}
 *     constructor parameter. It must be structured as described below.</li>
 *     <li>The class being visited <em>must</em> have a <em>static</em> method with the signature {@code ()V}. It must
 *     be named exactly as specified in the {@code decoderHook} constructor parameter.</li>
 *     <li>The class being visited <em>must</em> have a field of type {@code int} named {@code pc. This must be the
 *     actual program counter of the implementation. It will be updated by the generated code in case of early exits
 *     from the decoder loop, e.g. due to jumps.</li>
 * </ul>
 * <p>
 * The {@code decoderMethod} must have the following signature:
 * <pre>
 *     void(final T memoryAccess, int inst, int pc, int instOffset, final int instEnd)
 * </pre>
 * In particular, the {@code pc} and {@code instOffset} parameters <em>must not</em> be final because
 * the generated code will update them as execution proceeds. The {@code inst} will typically also not
 * be final, so that the next instruction can be fetched without exiting the current instruction sequence.
 * <p>
 * Semantically, the {@code decoderMethod} is expected to be used as such:
 * <ul>
 *     <li>It is called with a starting program counter address ({@code pc}), instruction ({@code inst}),
 *     a memory range in which instructions should be executed (defined by {@code instOffset} and {@code instEnd},
 *     and some means for fetching additional instructions ({@code memoryAccess}).</li>
 *     <li>It will keep fetching and decoding instructions until either the end of the defined memory range
 *     is reached, or an exception is raised by the decoder (illegal instruction exceptions) or the instructions
 *     being executed (implementation defined, typically memory access exceptions).</li>
 * </ul>
 * <p>
 * Example implementation of a {@code decoderMethod} and {@code decoderHook}:
 * <pre>
 * private void decoderMethod(final MemoryView view, int inst, int pc, int instOffset, final int instEnd) {
 *     try {
 *         for (; ; ) {
 *             decoderHook();
 *
 *             if (instOffset < instEnd) {
 *                 inst = view.load(instOffset, Sizes.SIZE_32_LOG2);
 *             } else {
 *                 this.pc = pc;
 *                 return;
 *             }
 *         }
 *     } catch (final IllegalInstructionException e) {
 *         this.pc = pc;
 *     }
 * }
 *
 * private static void decoderHook() {
 * }
 * </pre>
 */
public class DecoderGenerator extends ClassVisitor implements Opcodes {
    private final AbstractDecoderTreeNode decoderTree;
    private final Function<InstructionDeclaration, InstructionDefinition> definitionProvider;
    private final String decoderMethod;
    private final String decoderHook;
    private final String illegalInstructionInternalName;
    private String hostClassInternalName;

    public DecoderGenerator(final ClassVisitor cv,
                            final AbstractDecoderTreeNode decoderTree,
                            final Function<InstructionDeclaration, InstructionDefinition> definitionProvider,
                            final Class<?> illegalInstructionExceptionClass,
                            final String decoderMethod,
                            final String decoderHook) {
        super(ASM7, cv);
        this.decoderTree = decoderTree;
        this.definitionProvider = definitionProvider;
        this.decoderMethod = decoderMethod;
        this.decoderHook = decoderHook;
        this.illegalInstructionInternalName = Type.getInternalName(illegalInstructionExceptionClass);
    }

    protected void emitInstruction(final GeneratorContext context,
                                   final InstructionDeclaration declaration,
                                   final InstructionDefinition definition) {
        context.methodVisitor.visitVarInsn(ALOAD, GeneratorContext.LOCAL_THIS);
        for (final InstructionArgument argument : definition.parameters) {
            if (argument instanceof ConstantInstructionArgument) {
                final ConstantInstructionArgument constantArgument = (ConstantInstructionArgument) argument;
                context.methodVisitor.visitLdcInsn(constantArgument.value);
            } else if (argument instanceof ProgramCounterInstructionArgument) {
                context.methodVisitor.visitVarInsn(ILOAD, context.localPc);
            } else if (argument instanceof FieldInstructionArgument) {
                final FieldInstructionArgument fieldArgument = (FieldInstructionArgument) argument;
                if (context.localVariables.containsKey(fieldArgument)) {
                    final int localIndex = context.localVariables.getInt(fieldArgument);
                    context.methodVisitor.visitVarInsn(ILOAD, localIndex);
                } else {
                    context.emitGetField(fieldArgument);
                }
            } else {
                throw new IllegalArgumentException();
            }
        } // cpu, arg0, ..., argN

        final String methodDescriptor = "(" + StringUtils.repeat('I', definition.parameters.length) + ")"
                                        + (definition.returnsBoolean ? "Z" : "V");
        context.methodVisitor.visitMethodInsn(INVOKESPECIAL, hostClassInternalName,
                definition.methodName, methodDescriptor, false);

        if (definition.returnsBoolean) {
            final Label updateOffsetAndContinueLabel = new Label();
            context.methodVisitor.visitJumpInsn(IFEQ, updateOffsetAndContinueLabel);
            switch (context.type) {
                case TOP_LEVEL:
                    if (!definition.writesPC) {
                        context.emitIncrementPC(declaration.size);
                        context.emitSavePC();
                    }
                    context.methodVisitor.visitInsn(RETURN);
                    break;
                case CONDITIONAL_METHOD:
                    if (!definition.writesPC) {
                        context.methodVisitor.visitInsn(ICONST_0 + GeneratorContext.RETURN_EXIT_INC_PC);
                    } else {
                        context.methodVisitor.visitInsn(ICONST_0 + GeneratorContext.RETURN_EXIT);
                    }
                    context.methodVisitor.visitInsn(IRETURN);
                    break;
                default:
                    throw new IllegalStateException();
            }

            context.methodVisitor.visitLabel(updateOffsetAndContinueLabel);
            context.emitIncrementPC(declaration.size);
            context.emitContinue();
        } else if (definition.writesPC) {
            switch (context.type) {
                case TOP_LEVEL:
                    context.methodVisitor.visitInsn(RETURN);
                    break;
                case CONDITIONAL_METHOD:
                    context.methodVisitor.visitInsn(ICONST_0 + GeneratorContext.RETURN_EXIT);
                    context.methodVisitor.visitInsn(IRETURN);
                    break;
                default:
                    throw new IllegalStateException();
            }
        } else {
            context.emitIncrementPC(declaration.size);
            context.emitContinue();
        }
    }

    @Override
    public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        hostClassInternalName = name;
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
        if (decoderMethod.equals(name)) {
            return new TemplateMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions), super.cv);
        } else {
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    private final class TemplateMethodVisitor extends MethodVisitor implements Opcodes {
        private final ClassVisitor classVisitor;

        public TemplateMethodVisitor(final MethodVisitor methodVisitor, final ClassVisitor classVisitor) {
            super(Opcodes.ASM7, methodVisitor);
            this.classVisitor = classVisitor;
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            if (!decoderHook.equals(name)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

            decoderTree.accept(new DecoderTreeRootNodeVisitor(new GeneratorContext(classVisitor, super.mv)));
        }
    }

    private enum ContextType {
        TOP_LEVEL,
        VOID_METHOD,
        CONDITIONAL_METHOD,
    }

    protected final class GeneratorContext implements Opcodes {
        private static final int LOCAL_THIS = 0;
        private static final int LOCAL_INST = 2;
        private static final int LOCAL_PC = 3;
        private static final int LOCAL_INST_OFFSET = 4;
        private static final int LOCAL_FIRST_FIELD = 6;

        public static final int RETURN_CONTINUE = 0;
        public static final int RETURN_EXIT = 1;
        public static final int RETURN_EXIT_INC_PC = 2;
        public static final int RETURN_ILLEGAL_INSTRUCTION = 3;

        public final ClassVisitor classVisitor;
        public final MethodVisitor methodVisitor;
        public final ContextType type;
        public final int processedMask;
        public final int localInst;
        public final int localPc;
        public final int localFirstField;

        public final Label continueLabel;
        public final Label illegalInstructionLabel;
        private final Object2IntArrayMap<FieldInstructionArgument> localVariables;

        // Constructor for new top-level context.
        private GeneratorContext(final ClassVisitor classVisitor,
                                 final MethodVisitor methodVisitor) {
            this(classVisitor, methodVisitor, ContextType.TOP_LEVEL, 0,
                    LOCAL_INST, LOCAL_PC, LOCAL_FIRST_FIELD,
                    new Label(), new Label(), new Object2IntArrayMap<>());
        }

        // Constructor for nested context.
        private GeneratorContext(final ClassVisitor classVisitor,
                                 final MethodVisitor methodVisitor,
                                 final ContextType type,
                                 final int processedMask,
                                 final Object2IntArrayMap<FieldInstructionArgument> localVariables) {
            this(classVisitor, methodVisitor, type, processedMask,
                    1, // inst is always first arg
                    2, // pc is always second arg
                    3 + localVariables.size(), // this + pc + inst + nargs
                    new Label(), new Label(), localVariables);
        }

        private GeneratorContext(final ClassVisitor classVisitor,
                                 final MethodVisitor methodVisitor,
                                 final ContextType type,
                                 final int processedMask,
                                 final int localInst,
                                 final int localPc,
                                 final int localFirstField,
                                 final Label continueLabel,
                                 final Label illegalInstructionLabel,
                                 final Object2IntArrayMap<FieldInstructionArgument> localVariables) {
            this.classVisitor = classVisitor;
            this.methodVisitor = methodVisitor;
            this.type = type;
            this.processedMask = processedMask;
            this.localInst = localInst;
            this.localPc = localPc;
            this.localFirstField = localFirstField;
            this.continueLabel = continueLabel;
            this.illegalInstructionLabel = illegalInstructionLabel;
            this.localVariables = localVariables;
        }

        public GeneratorContext withProcessed(final int mask) {
            return new GeneratorContext(classVisitor, methodVisitor, type,
                    processedMask | mask, localInst, localPc, localFirstField,
                    continueLabel, illegalInstructionLabel, localVariables);
        }

        public void emitContinue() {
            switch (type) {
                case TOP_LEVEL:
                    methodVisitor.visitJumpInsn(GOTO, continueLabel);
                    break;
                case VOID_METHOD:
                    methodVisitor.visitInsn(RETURN);
                    break;
                case CONDITIONAL_METHOD:
                    methodVisitor.visitInsn(ICONST_0 + GeneratorContext.RETURN_CONTINUE);
                    methodVisitor.visitInsn(IRETURN);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        public void emitThrowIllegalInstruction() {
            methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);
        }

        public void emitIncrementPC(final int value) {
            if (type == ContextType.TOP_LEVEL) {
                methodVisitor.visitIincInsn(LOCAL_PC, value);
                methodVisitor.visitIincInsn(LOCAL_INST_OFFSET, value);
            } // else: caller will increment for us.
        }

        public void emitSavePC() {
            assert type == ContextType.TOP_LEVEL;
            methodVisitor.visitVarInsn(ALOAD, LOCAL_THIS);
            methodVisitor.visitVarInsn(ILOAD, LOCAL_PC);
            methodVisitor.visitFieldInsn(PUTFIELD, hostClassInternalName, "pc", "I");
        }

        public void emitGetField(final FieldInstructionArgument argument) {
            methodVisitor.visitInsn(ICONST_0);
            for (final InstructionFieldMapping mapping : argument.mappings) {
                methodVisitor.visitVarInsn(ILOAD, localInst);
                methodVisitor.visitLdcInsn(mapping.srcLSB);
                methodVisitor.visitLdcInsn(mapping.srcMSB);
                methodVisitor.visitLdcInsn(mapping.dstLSB);
                methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BitUtils.class),
                        "getField", "(IIII)I", false);
                if (mapping.signExtend) {
                    methodVisitor.visitLdcInsn(mapping.dstLSB + (mapping.srcMSB - mapping.srcLSB) + 1);
                    methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BitUtils.class),
                            "extendSign", "(II)I", false);
                }
                methodVisitor.visitInsn(IOR);
            }

            switch (argument.postprocessor) {
                case NONE:
                    break;
                case ADD_8:
                    methodVisitor.visitLdcInsn(8);
                    methodVisitor.visitInsn(IADD);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    private final class DecoderTreeRootNodeVisitor implements DecoderTreeVisitor, Opcodes {
        private final GeneratorContext context;

        public DecoderTreeRootNodeVisitor(final GeneratorContext context) {
            this.context = context;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            return new SwitchVisitor(context);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            return new BranchVisitor(context);
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(context);
        }

        @Override
        public void visitEnd() {
            context.methodVisitor.visitLabel(context.illegalInstructionLabel);
            context.methodVisitor.visitTypeInsn(NEW, illegalInstructionInternalName);
            context.methodVisitor.visitInsn(DUP);
            context.methodVisitor.visitMethodInsn(INVOKESPECIAL, illegalInstructionInternalName,
                    "<init>", "()V", false);
            context.methodVisitor.visitInsn(ATHROW);

            context.methodVisitor.visitLabel(context.continueLabel);
        }
    }

    private final class InnerNodeVisitor implements DecoderTreeVisitor, Opcodes {
        private final GeneratorContext context;
        private final Label endLabel;

        private GeneratorContext childContext;

        public InnerNodeVisitor(final GeneratorContext context, final Label endLabel) {
            this.context = context;
            this.endLabel = endLabel;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch(final DecoderTreeSwitchNode node) {
            return new SwitchVisitor(generateMethodInvocation(node));
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch(final DecoderTreeBranchNode node) {
            return new BranchVisitor(generateMethodInvocation(node));
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(context);
        }

        @Override
        public void visitEnd() {
            if (childContext != null) {
                childContext.methodVisitor.visitLabel(childContext.illegalInstructionLabel);
                childContext.methodVisitor.visitTypeInsn(NEW, illegalInstructionInternalName);
                childContext.methodVisitor.visitInsn(DUP);
                childContext.methodVisitor.visitMethodInsn(INVOKESPECIAL, illegalInstructionInternalName,
                        "<init>", "()V", false);
                childContext.methodVisitor.visitInsn(ATHROW);

                switch (childContext.type) {
                    case VOID_METHOD: {
                        childContext.methodVisitor.visitLabel(childContext.continueLabel);
                        childContext.methodVisitor.visitInsn(RETURN);
                        break;
                    }
                    case CONDITIONAL_METHOD: {
                        childContext.methodVisitor.visitLabel(childContext.continueLabel);
                        childContext.methodVisitor.visitInsn(ICONST_0 + GeneratorContext.RETURN_CONTINUE);
                        childContext.methodVisitor.visitInsn(IRETURN);
                        break;
                    }
                    default: {
                        throw new IllegalStateException();
                    }
                }

                childContext.methodVisitor.visitMaxs(-1, -1);
                childContext.methodVisitor.visitEnd();
            }

            if (endLabel != null) {
                context.methodVisitor.visitLabel(endLabel);
            }
        }

        private GeneratorContext generateMethodInvocation(final AbstractDecoderTreeNode node) {
            final List<InstructionDeclaration> instructions = node.getInstructions().collect(Collectors.toList());
            if (instructions.size() == 1) {
                return context;
            }

            final OptionalInt commonInstructionSize = computeCommonInstructionSize(node);
            if (!commonInstructionSize.isPresent()) {
                return context;
            }

            // Build list of arguments needed by instructions we're grouping into a method that we
            // have already sitting around in a local variable, so we can just pass those along as
            // parameters instead of having to re-compute them in the method.
            final ArrayList<FieldInstructionArgument> parameters = new ArrayList<>(node.getArguments().arguments.keySet());
            parameters.retainAll(context.localVariables.keySet());

            final Object2IntArrayMap<FieldInstructionArgument> localsInMethod = new Object2IntArrayMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                localsInMethod.put(parameters.get(i), 3 + i); // this + pc + inst
            }

            // If any of the instructions we're about to group into a method can lead to an early return,
            // either after updating the PC themselves, or just to break out of the current trace, e.g.
            // because caches have been invalidated, we need to pass this along from our generated method.
            // As such, the generated method must have a return type. We use three int values to indicate
            // the different states: 0 = advance PC and continue, 1 = exit, 2 = advance PC and exit.
            final List<InstructionDefinition> definitions = instructions.stream()
                    .map(definitionProvider)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            final boolean containsReturns = definitions.stream().anyMatch(d -> d.writesPC || d.returnsBoolean);

            final String methodName = instructions.stream()
                                              .map(i -> i.displayName.replaceAll("[^a-z^A-Z]", "_"))
                                              .collect(Collectors.joining("$")) + "." + System.nanoTime();
            final String methodDescriptor = "(" + StringUtils.repeat('I', 2 + parameters.size()) + ")" + (containsReturns ? "I" : "V");

            context.methodVisitor.visitVarInsn(ALOAD, GeneratorContext.LOCAL_THIS);
            context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
            context.methodVisitor.visitVarInsn(ILOAD, context.localPc);
            for (final FieldInstructionArgument parameter : parameters) {
                final int localIndex = context.localVariables.getInt(parameter);
                context.methodVisitor.visitVarInsn(ILOAD, localIndex);
            }
            context.methodVisitor.visitMethodInsn(INVOKESPECIAL, hostClassInternalName,
                    methodName, methodDescriptor, false);

            if (containsReturns) {
                final Label[] conditionalLabels = new Label[4];
                for (int i = 0; i < conditionalLabels.length; i++) {
                    conditionalLabels[i] = new Label();
                }

                switch (context.type) {
                    case TOP_LEVEL: {
                        context.methodVisitor.visitTableSwitchInsn(0, conditionalLabels.length - 1, context.illegalInstructionLabel, conditionalLabels);

                        context.methodVisitor.visitLabel(conditionalLabels[GeneratorContext.RETURN_CONTINUE]);
                        context.emitIncrementPC(commonInstructionSize.getAsInt());
                        context.methodVisitor.visitJumpInsn(GOTO, context.continueLabel);

                        context.methodVisitor.visitLabel(conditionalLabels[GeneratorContext.RETURN_EXIT]);
                        context.methodVisitor.visitInsn(RETURN);

                        context.methodVisitor.visitLabel(conditionalLabels[GeneratorContext.RETURN_EXIT_INC_PC]);
                        context.emitIncrementPC(commonInstructionSize.getAsInt());
                        context.emitSavePC();
                        context.methodVisitor.visitInsn(RETURN);

                        context.methodVisitor.visitLabel(conditionalLabels[GeneratorContext.RETURN_ILLEGAL_INSTRUCTION]);
                        context.emitThrowIllegalInstruction();

                        break;
                    }
                    case CONDITIONAL_METHOD: {
                        // We can't do any changes to PC either, so keep bubbling up.
                        context.methodVisitor.visitInsn(IRETURN);
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
            } else {
                context.emitIncrementPC(commonInstructionSize.getAsInt());
                context.emitContinue();
            }

            final String[] exceptions = definitions.stream()
                    .map(d -> d.thrownExceptions)
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .distinct()
                    .toArray(String[]::new);

            final MethodVisitor childVisitor = context.classVisitor.visitMethod(ACC_PRIVATE,
                    methodName, methodDescriptor, null, exceptions);
            childVisitor.visitCode();

            childContext = new GeneratorContext(
                    context.classVisitor,
                    childVisitor,
                    containsReturns ? ContextType.CONDITIONAL_METHOD : ContextType.VOID_METHOD,
                    context.processedMask,
                    localsInMethod);

            return childContext;
        }

        private OptionalInt computeCommonInstructionSize(final AbstractDecoderTreeNode node) {
            final List<Integer> sizes = node.getInstructions().map(i -> i.size).distinct().collect(Collectors.toList());
            return sizes.size() == 1 ? OptionalInt.of(sizes.get(0)) : OptionalInt.empty();
        }
    }

    private final class SwitchVisitor extends LocalVariableOwner implements DecoderTreeSwitchVisitor {
        private final Label defaultCase = new Label();
        private Label[] cases;
        private int switchMask;

        public SwitchVisitor(final GeneratorContext context) {
            super(context);
        }

        @Override
        public void visit(final int mask, final int[] patterns, final DecoderTreeNodeArguments arguments) {
            pushLocalVariables(arguments);

            final int caseCount = patterns.length;
            cases = new Label[caseCount];
            for (int i = 0; i < caseCount; i++) {
                cases[i] = new Label();
            }

            switchMask = mask & ~context.processedMask;
            final int unprocessedMask = mask & ~context.processedMask;
            final ArrayList<MaskField> maskFields = MaskField.create(unprocessedMask);

            // If we have mask fields that are the same in all patterns, generate an early-out if.
            // Not so much because of the early out, but because it allows us to simplify the switch
            // and increases the likelihood that we'll get sequential patterns.
            final ArrayList<MaskField> maskFieldsWithEqualPatterns = new ArrayList<>();
            for (int i = maskFields.size() - 1; i >= 0; i--) {
                final int fieldMask = maskFields.get(i).asMask();
                final int pattern = patterns[0] & fieldMask;

                boolean patternsMatch = true;
                for (int j = 1; j < caseCount; j++) {
                    if ((patterns[j] & fieldMask) != pattern) {
                        patternsMatch = false;
                        break;
                    }
                }

                if (patternsMatch) {
                    maskFieldsWithEqualPatterns.add(maskFields.remove(i));
                }
            }

            if (maskFields.isEmpty()) {
                throw new IllegalStateException(String.format("All cases in a switch node have the same patterns: [%s]",
                        maskFieldsWithEqualPatterns.stream().map(f -> Integer.toBinaryString(patterns[0] & f.asMask())).collect(Collectors.joining(", "))));
            }

            if (!maskFieldsWithEqualPatterns.isEmpty()) {
                int commonMask = 0;
                for (final MaskField maskField : maskFieldsWithEqualPatterns) {
                    commonMask |= maskField.asMask();
                }
                final int commonPattern = patterns[0] & commonMask;

                final Label switchLabel = new Label();

                context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                context.methodVisitor.visitLdcInsn(commonMask);
                context.methodVisitor.visitInsn(IAND);
                context.methodVisitor.visitLdcInsn(commonPattern);
                context.methodVisitor.visitJumpInsn(IF_ICMPEQ, switchLabel);
                context.emitThrowIllegalInstruction();
                context.methodVisitor.visitLabel(switchLabel);
            }

            // Try compressing mask by making mask adjacent and see if patterns also
            // compressed this way lead to a sequence, enabling a table switch.
            // TODO Test different field permutations?
            final int[] tablePatterns = new int[caseCount];
            for (int i = 0; i < caseCount; i++) {
                int tablePattern = 0;
                int offset = 0;
                for (final MaskField maskField : maskFields) {
                    tablePattern |= ((patterns[i] & maskField.asMask()) >>> maskField.srcLSB) << offset;
                    offset += maskField.srcMSB - maskField.srcLSB + 1;
                }
                tablePatterns[i] = tablePattern;
            }

            final Label[] tableLabels = sortPatternsAndGetRemappedLabels(tablePatterns);

            // Decide whether to do a tableswitch or lookupswitch. This uses the same heuristic
            // as javac: estimate space and time cost are accounted for, where time cost is weighted
            // more strongly (that's the *3).
            final int tableMax = tablePatterns[tablePatterns.length - 1];
            final int tableMin = tablePatterns[0];
            final int tableSize = tableMax - tableMin + 1;
            final int tableSpaceCost = 4 + tableSize;
            final int tableTimeCost = 3; // Comparisons if in range.
            final int tableInstructionMaskingCost; // Take into account instruction field extraction operations.
            if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                tableInstructionMaskingCost = 0;
            } else {
                tableInstructionMaskingCost = 1 + ((maskFields.get(0).srcLSB == 0) ? 4 : 6) + (maskFields.size() - 1) * (3 + 2 + 2 + 1) - 3;
            }
            final int lookupSpaceCost = 3 + 2 * tablePatterns.length;
            final int lookupTimeCost = tablePatterns.length;
            if (tableInstructionMaskingCost + tableSpaceCost + 3 * tableTimeCost <= lookupSpaceCost + 3 * lookupTimeCost) { // TableSwitch
                // Fill potential gaps.
                final Label[] labels = new Label[tableSize];
                int currentPattern = 0;
                for (int i = 0; i < tableSize; i++) {
                    if (tablePatterns[currentPattern] == tableMin + i) {
                        labels[i] = tableLabels[currentPattern];
                        currentPattern++;
                    } else {
                        labels[i] = defaultCase;
                    }
                }

                if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                    // Trivial case: no shifting needed to mask instruction.
                    for (int i = 0; i < caseCount; i++) {
                        assert (patterns[i] & unprocessedMask) == (tablePatterns[i] & unprocessedMask);
                    }
                    context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                    context.methodVisitor.visitLdcInsn(unprocessedMask);
                    context.methodVisitor.visitInsn(IAND);
                } else {
                    // General case: mask out fields from instruction and most importantly shift them down
                    // to be adjacent so the instruction can match our table patterns.
                    context.methodVisitor.visitInsn(ICONST_0);
                    int offset = 0;
                    for (final MaskField maskField : maskFields) {
                        context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                        context.methodVisitor.visitLdcInsn(maskField.asMask());
                        context.methodVisitor.visitInsn(IAND);
                        if (maskField.srcLSB > 0) {
                            context.methodVisitor.visitLdcInsn(maskField.srcLSB);
                            context.methodVisitor.visitInsn(IUSHR);
                            if (offset > 0) {
                                context.methodVisitor.visitLdcInsn(offset);
                                context.methodVisitor.visitInsn(ISHL);
                            }
                        }
                        context.methodVisitor.visitInsn(IOR);
                        offset += maskField.srcMSB - maskField.srcLSB + 1;
                    }
                }
                context.methodVisitor.visitTableSwitchInsn(tableMin, tableMax, defaultCase, labels);
            } else { // LookupSwitch
                // Java requires switch values to be in signed sorted order. We'll get visited in the original
                // order of the patterns in the node's pattern list (which we must not change), so we need to
                // pass a remapped labels array to the switch instruction.
                final int[] sortedPatterns = new int[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    sortedPatterns[i] = patterns[i] & mask;
                }
                final Label[] labels = sortPatternsAndGetRemappedLabels(sortedPatterns);

                // For non-sequential patterns we have to create a lookup switch.
                context.methodVisitor.visitVarInsn(ILOAD, context.localInst);
                context.methodVisitor.visitLdcInsn(mask);
                context.methodVisitor.visitInsn(IAND);
                context.methodVisitor.visitLookupSwitchInsn(defaultCase, sortedPatterns, labels);
            }
        }

        @Override
        public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
            context.methodVisitor.visitLabel(cases[index]);
            return new InnerNodeVisitor(context.withProcessed(switchMask), null);
        }

        @Override
        public void visitEnd() {
            context.methodVisitor.visitLabel(defaultCase);
            context.emitThrowIllegalInstruction();

            popVariables();
        }

        private Label[] sortPatternsAndGetRemappedLabels(final int[] patterns) {
            final int caseCount = patterns.length;

            final PatternAndLabel[] compressedPatternsAndLabels = new PatternAndLabel[caseCount];
            for (int i = 0; i < caseCount; i++) {
                compressedPatternsAndLabels[i] = new PatternAndLabel(patterns[i], cases[i]);
            }

            Arrays.sort(compressedPatternsAndLabels);

            final Label[] labels = new Label[caseCount];
            for (int i = 0; i < caseCount; i++) {
                patterns[i] = compressedPatternsAndLabels[i].pattern;
                labels[i] = compressedPatternsAndLabels[i].label;
            }

            return labels;
        }
    }

    private final class BranchVisitor extends LocalVariableOwner implements DecoderTreeBranchVisitor {
        public BranchVisitor(final GeneratorContext context) {
            super(context);
        }

        @Override
        public void visit(final int count, final DecoderTreeNodeArguments arguments) {
            pushLocalVariables(arguments);
        }

        @Override
        public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
            final int remainingMask = mask & ~context.processedMask;
            if (remainingMask != 0) {
                final Label elseLabel = new Label();
                context.methodVisitor.visitVarInsn(ILOAD, context.localInst); // inst
                context.methodVisitor.visitLdcInsn(remainingMask); // inst, mask
                context.methodVisitor.visitInsn(IAND); // instMasked
                context.methodVisitor.visitLdcInsn(pattern & ~context.processedMask); // inst, pattern
                context.methodVisitor.visitJumpInsn(IF_ICMPNE, elseLabel);
                return new InnerNodeVisitor(context.withProcessed(remainingMask), elseLabel);
            } else {
                return new InnerNodeVisitor(context.withProcessed(remainingMask), null);
            }
        }

        @Override
        public void visitEnd() {
            context.emitThrowIllegalInstruction();

            popVariables();
        }
    }

    private final class LeafVisitor implements DecoderTreeLeafVisitor, Opcodes {
        private final GeneratorContext context;

        public LeafVisitor(final GeneratorContext context) {
            this.context = context;
        }

        @Override
        public void visitInstruction(final InstructionDeclaration declaration) {
            if (declaration.type == InstructionType.ILLEGAL) {
                context.emitThrowIllegalInstruction();
                return;
            }

            if (declaration.type == InstructionType.HINT) {
                context.emitIncrementPC(declaration.size);
                context.emitContinue();
                return;
            }

            final InstructionDefinition definition = definitionProvider.apply(declaration);
            if (definition == null) {
                context.emitThrowIllegalInstruction();
                return;
            }

            emitInstruction(context, declaration, definition);
        }

        @Override
        public void visitEnd() {
        }
    }

    private static abstract class LocalVariableOwner implements Opcodes {
        // This is our threshold for pulling field extraction out of individual instruction leaf nodes into
        // a switch or branch node. 0 = pull up everything used more than once, 1 = pull up nothing,
        // everything in-between is the relative value of used/total that has to be exceeded for a field
        // extraction to be pulled up.
        // The value of 0.5 has been empirically determined as roughly a sweet-spot for the time being.
        private static final float THRESHOLD = 0.5f;

        private final HashMap<FieldInstructionArgument, String> ownedLocals = new HashMap<>();
        private final Label beginVariableScopeLabel = new Label();

        protected final GeneratorContext context;

        protected LocalVariableOwner(final GeneratorContext context) {
            this.context = context;
        }

        protected void pushLocalVariables(final DecoderTreeNodeArguments arguments) {
            final int threshold = Math.max(2, (int) (arguments.totalLeafCount * THRESHOLD));
            arguments.arguments.forEach((argument, entry) -> {
                if (entry.count >= threshold && !context.localVariables.containsKey(argument)) {
                    ownedLocals.put(argument, String.join("_", entry.names));
                }
            });

            if (ownedLocals.isEmpty()) {
                return;
            }

            for (final FieldInstructionArgument argument : ownedLocals.keySet()) {
                // This works because while it's a map, we fill and empty it like a stack.
                final int localIndex = context.localFirstField + context.localVariables.size();
                context.localVariables.put(argument, localIndex);

                context.emitGetField(argument);
                context.methodVisitor.visitVarInsn(ISTORE, localIndex);
            }

            context.methodVisitor.visitLabel(beginVariableScopeLabel);
        }

        protected void popVariables() {
            if (ownedLocals.isEmpty()) {
                return;
            }

            final Label endVariableScopeLabel = new Label();
            context.methodVisitor.visitLabel(endVariableScopeLabel);

            for (final Map.Entry<FieldInstructionArgument, String> entry : ownedLocals.entrySet()) {
                final FieldInstructionArgument argument = entry.getKey();
                final int localIndex = context.localVariables.removeInt(argument);
                context.methodVisitor.visitLocalVariable(entry.getValue(), "I", null, beginVariableScopeLabel, endVariableScopeLabel, localIndex);
            }
        }
    }

    private static final class PatternAndLabel implements Comparable<PatternAndLabel> {
        public final int pattern;
        public final Label label;

        private PatternAndLabel(final int pattern, final Label label) {
            this.pattern = pattern;
            this.label = label;
        }

        @Override
        public int compareTo(final PatternAndLabel o) {
            return Integer.compare(pattern, o.pattern);
        }
    }

    private static final class MaskField {
        public static ArrayList<MaskField> create(int mask) {
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

        public final int srcMSB;
        public final int srcLSB;

        private MaskField(final int srcMSB, final int srcLSB) {
            this.srcMSB = srcMSB;
            this.srcLSB = srcLSB;
        }

        public int asMask() {
            return BitUtils.maskFromRange(srcLSB, srcMSB);
        }
    }
}
