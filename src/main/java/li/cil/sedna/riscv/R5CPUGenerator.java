package li.cil.sedna.riscv;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.*;
import li.cil.sedna.instruction.decoder.*;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.utils.BitUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.core.util.Throwables;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class R5CPUGenerator {
    private static final Class<?> GENERATED_CLASS;
    private static final Constructor<?> GENERATED_CLASS_CTOR;

    static R5CPU create(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        try {
            return (R5CPU) GENERATED_CLASS_CTOR.newInstance(physicalMemory, rtc);
        } catch (final InvocationTargetException e) {
            Throwables.rethrow(e.getCause());
            throw new AssertionError();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static {
        GENERATED_CLASS = generateClass();
        try {
            GENERATED_CLASS_CTOR = GENERATED_CLASS.getConstructor(MemoryMap.class, RealTimeCounter.class);
            GENERATED_CLASS_CTOR.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> generateClass() {
        try {
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            final ClassRemapper remapper = new ClassRemapper(writer, new Remapper() {
                private final String TEMPLATE_NAME = Type.getInternalName(R5CPUTemplate.class);

                @Override
                public String map(final String internalName) {
                    if (internalName.equals(TEMPLATE_NAME)) {
                        return TEMPLATE_NAME + "$Generated";
                    } else {
                        return super.map(internalName);
                    }
                }
            });

            final ClassReader reader = new ClassReader(R5CPUTemplate.class.getName());
            reader.accept(new TemplateClassVisitor(remapper), ClassReader.EXPAND_FRAMES);

            final byte[] bytes = writer.toByteArray();

            final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(R5CPUTemplate.class.getClassLoader(), null, bytes, 0, bytes.length);
        } catch (final Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static final class TemplateClassVisitor extends ClassVisitor implements Opcodes {
        public TemplateClassVisitor(final ClassVisitor cv) {
            super(ASM7, cv);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
            if ("interpretTrace".equals(name)) {
                return new TemplateMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions));
            } else {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }

        @Override
        public void visitInnerClass(final String name, final String outerName, final String innerName, final int access) {
        }
    }

    private static final class TemplateMethodVisitor extends MethodVisitor implements Opcodes {
        public TemplateMethodVisitor(final MethodVisitor mv) {
            super(Opcodes.ASM7, mv);
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            if (!"decode".equals(name)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

            final Label continueLabel = new Label();
            R5Instructions.DECODER.accept(new DecoderGenerator(super.mv, continueLabel));
            super.visitLabel(continueLabel);
        }
    }

    private static final class DecoderGenerator implements DecoderTreeVisitor, Opcodes {
        private static final int LOCAL_THIS = 0;
        private static final int LOCAL_CACHE = 1;
        private static final int LOCAL_INST = 2;
        private static final int LOCAL_INST_OFFSET = 3;
        private static final int LOCAL_TO_PC = 4;
        private static final int LOCAL_INST_END = 5;
        private static final int LOCAL_FIRST_FIELD = 6;

        private final MethodVisitor methodVisitor;
        private final Label continueLabel;
        private final Label illegalInstructionLabel;
        private final Object2IntArrayMap<FieldInstructionArgument> localVariables = new Object2IntArrayMap<>();

        private DecoderGenerator(final MethodVisitor methodVisitor, final Label continueLabel) {
            this.methodVisitor = methodVisitor;
            this.continueLabel = continueLabel;
            this.illegalInstructionLabel = new Label();
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch() {
            return new SwitchVisitor(0);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch() {
            return new BranchVisitor(0);
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor();
        }

        @Override
        public void visitEnd() {
            methodVisitor.visitLabel(illegalInstructionLabel);
            methodVisitor.visitTypeInsn(NEW, Type.getInternalName(R5IllegalInstructionException.class));
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5IllegalInstructionException.class), "<init>", "()V", false);
            methodVisitor.visitInsn(ATHROW);
        }

        private final class InnerNodeVisitor implements DecoderTreeVisitor {
            private final int processedMask;
            private final Label endLabel;

            public InnerNodeVisitor(final int processedMask, final Label endLabel) {
                this.processedMask = processedMask;
                this.endLabel = endLabel;
            }

            @Override
            public DecoderTreeSwitchVisitor visitSwitch() {
                return new SwitchVisitor(processedMask);
            }

            @Override
            public DecoderTreeBranchVisitor visitBranch() {
                return new BranchVisitor(processedMask);
            }

            @Override
            public DecoderTreeLeafVisitor visitInstruction() {
                return new LeafVisitor();
            }

            @Override
            public void visitEnd() {
                if (endLabel != null) {
                    methodVisitor.visitLabel(endLabel);
                }
            }
        }

        private final class SwitchVisitor extends LocalVariableContext implements DecoderTreeSwitchVisitor {
            private final int processedMask;
            private final Label defaultCase = new Label();
            private Label[] cases;
            private int switchMask;

            public SwitchVisitor(final int processedMask) {
                this.processedMask = processedMask;
            }

            @Override
            public void visit(final DecoderTreeSwitchNode node) {
                pushLocalVariables(node.arguments());

                final AbstractDecoderTreeNode[] children = node.children;
                final int caseCount = children.length;

                cases = new Label[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    cases[i] = new Label();
                }

                switchMask = node.mask() & ~processedMask;
                final int mask = node.mask() & ~processedMask;
                final ArrayList<MaskField> maskFields = computeMaskFields(mask);

                // If we have mask fields that are the same in all patterns, generate an early-out if.
                // Not so much because of the early out, but because it allows us to simplify the switch
                // and increases the likelihood that we'll get sequential patterns.
                final ArrayList<MaskField> maskFieldsWithEqualPatterns = new ArrayList<>();
                for (int i = maskFields.size() - 1; i >= 0; i--) {
                    final int fieldMask = maskFields.get(i).asMask();
                    final int pattern = children[0].pattern() & fieldMask;

                    boolean patternsMatch = true;
                    for (int j = 1; j < caseCount; j++) {
                        if ((children[j].pattern() & fieldMask) != pattern) {
                            patternsMatch = false;
                            break;
                        }
                    }

                    if (patternsMatch) {
                        maskFieldsWithEqualPatterns.add(maskFields.remove(i));
                    }
                }

                if (maskFields.isEmpty()) {
                    throw new IllegalStateException(String.format("All cases in a switch node have the same pattern: [%s]",
                            Arrays.stream(children).map(Object::toString).collect(Collectors.joining(", "))));
                }

                if (!maskFieldsWithEqualPatterns.isEmpty()) {
                    int commonMask = 0;
                    for (final MaskField maskField : maskFieldsWithEqualPatterns) {
                        commonMask |= maskField.asMask();
                    }
                    final int commonPattern = children[0].pattern() & commonMask;

                    final Label switchLabel = new Label();

                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                    methodVisitor.visitLdcInsn(commonMask);
                    methodVisitor.visitInsn(IAND);
                    methodVisitor.visitLdcInsn(commonPattern);
                    methodVisitor.visitJumpInsn(IF_ICMPEQ, switchLabel);
                    methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);
                    methodVisitor.visitLabel(switchLabel);
                }

                // Try compressing mask by making mask adjacent and see if patterns also
                // compressed this way lead to a sequence, enabling a table switch.
                // TODO Test different field permutations?
                final int[] tablePatterns = new int[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    int tablePattern = 0;
                    int offset = 0;
                    for (final MaskField maskField : maskFields) {
                        tablePattern |= ((children[i].pattern() & maskField.asMask()) >>> maskField.srcLSB) << offset;
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
                            assert (children[i].pattern() & mask) == (tablePatterns[i] & mask);
                        }
                        methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                        methodVisitor.visitLdcInsn(mask);
                        methodVisitor.visitInsn(IAND);
                    } else {
                        // General case: mask out fields from instruction and most importantly shift them down
                        // to be adjacent so the instruction can match our table patterns.
                        methodVisitor.visitInsn(ICONST_0);
                        int offset = 0;
                        for (final MaskField maskField : maskFields) {
                            methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                            methodVisitor.visitLdcInsn(maskField.asMask());
                            methodVisitor.visitInsn(IAND);
                            if (maskField.srcLSB > 0) {
                                methodVisitor.visitLdcInsn(maskField.srcLSB);
                                methodVisitor.visitInsn(IUSHR);
                                if (offset > 0) {
                                    methodVisitor.visitLdcInsn(offset);
                                    methodVisitor.visitInsn(ISHL);
                                }
                            }
                            methodVisitor.visitInsn(IOR);
                            offset += maskField.srcMSB - maskField.srcLSB + 1;
                        }
                    }
                    methodVisitor.visitTableSwitchInsn(tableMin, tableMax, defaultCase, labels);
                } else { // LookupSwitch
                    // Java requires switch values to be in signed sorted order. We'll get visited in the original
                    // order of the patterns in the node's pattern list (which we must not change), so we need to
                    // pass a remapped labels array to the switch instruction.
                    final int[] patterns = new int[caseCount];
                    for (int i = 0; i < caseCount; i++) {
                        patterns[i] = children[i].pattern() & node.mask();
                    }
                    final Label[] labels = sortPatternsAndGetRemappedLabels(patterns);

                    // For non-sequential patterns we have to create a lookup switch.
                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                    methodVisitor.visitLdcInsn(node.mask());
                    methodVisitor.visitInsn(IAND);
                    methodVisitor.visitLookupSwitchInsn(defaultCase, patterns, labels);
                }
            }

            @Override
            public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
                methodVisitor.visitLabel(cases[index]);
                return new InnerNodeVisitor(processedMask | switchMask, null);
            }

            @Override
            public void visitEnd() {
                methodVisitor.visitLabel(defaultCase);
                methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);

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

        private final class BranchVisitor extends LocalVariableContext implements DecoderTreeBranchVisitor {
            private final int processedMask;

            public BranchVisitor(final int processedMask) {
                this.processedMask = processedMask;
            }

            @Override
            public void visit(final int count, final DecoderTreeNodeFieldInstructionArguments arguments) {
                pushLocalVariables(arguments);
            }

            @Override
            public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
                final int remainingMask = mask & ~processedMask;
                if (remainingMask != 0) {
                    final Label elseLabel = new Label();
                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST); // inst
                    methodVisitor.visitLdcInsn(remainingMask); // inst, mask
                    methodVisitor.visitInsn(IAND); // instMasked
                    methodVisitor.visitLdcInsn(pattern & ~processedMask); // inst, pattern
                    methodVisitor.visitJumpInsn(IF_ICMPNE, elseLabel);
                    return new InnerNodeVisitor(processedMask | remainingMask, elseLabel);
                } else {
                    return new InnerNodeVisitor(processedMask | remainingMask, null);
                }
            }

            @Override
            public void visitEnd() {
                methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);

                popVariables();
            }
        }

        private class LeafVisitor implements DecoderTreeLeafVisitor {
            @Override
            public void visitInstruction(final InstructionDeclaration declaration) {
                if (declaration.type == InstructionType.ILLEGAL) {
                    methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);
                    return;
                }

                if (declaration.type == InstructionType.HINT) {
                    generateInstOffsetInc(declaration.size);
                    methodVisitor.visitJumpInsn(GOTO, continueLabel);
                    return;
                }

                final InstructionDefinition definition = R5Instructions.getDefinition(declaration);
                if (definition == null) {
                    methodVisitor.visitJumpInsn(GOTO, illegalInstructionLabel);
                    return;
                }

                if (definition.readsPC) {
                    generateSavePC();
                }

                methodVisitor.visitVarInsn(ALOAD, LOCAL_THIS); // cpu
                for (final InstructionArgument argument : definition.parameters) {
                    if (argument instanceof ConstantInstructionArgument) {
                        final ConstantInstructionArgument constantArgument = (ConstantInstructionArgument) argument;
                        methodVisitor.visitLdcInsn(constantArgument.value);
                    } else if (argument instanceof FieldInstructionArgument) {
                        final FieldInstructionArgument fieldArgument = (FieldInstructionArgument) argument;
                        if (localVariables.containsKey(fieldArgument)) {
                            final int localIndex = localVariables.getInt(fieldArgument);
                            methodVisitor.visitVarInsn(ILOAD, localIndex);
                        } else {
                            generateGetField(fieldArgument);
                        }
                    } else {
                        throw new IllegalArgumentException();
                    }
                } // cpu, arg0, ..., argN

                final String methodDescriptor = "(" + StringUtils.repeat('I', definition.parameters.length) + ")"
                                                + (definition.returnsBoolean ? "Z" : "V");
                methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5CPUTemplate.class),
                        definition.methodName, methodDescriptor, false);

                if (definition.returnsBoolean) {
                    final Label updateOffsetAndContinueLabel = new Label();
                    methodVisitor.visitJumpInsn(IFEQ, updateOffsetAndContinueLabel);
                    if (!definition.writesPC) {
                        generateInstOffsetInc(declaration.size);
                        generateSavePC();
                    }
                    methodVisitor.visitInsn(RETURN);

                    methodVisitor.visitLabel(updateOffsetAndContinueLabel);
                    generateInstOffsetInc(declaration.size);
                    methodVisitor.visitJumpInsn(GOTO, continueLabel);
                } else if (definition.writesPC) {
                    methodVisitor.visitInsn(RETURN);
                } else {
                    generateInstOffsetInc(declaration.size);
                    methodVisitor.visitJumpInsn(GOTO, continueLabel);
                }
            }

            @Override
            public void visitEnd() {
            }
        }

        private abstract class LocalVariableContext {
            // This is our threshold for pulling field extraction out of individual instruction leaf nodes into
            // a switch or branch node. 0 = pull up everything used more than once, 1 = pull up nothing,
            // everything in-between is the relative value of used/total that has to be exceeded for a field
            // extraction to be pulled up.
            // The value of 0.5 has been empirically determined as roughly a sweet-spot for the time being.
            private static final float THRESHOLD = 0.5f;

            private final HashMap<FieldInstructionArgument, String> ownedLocals = new HashMap<>();
            private final Label beginVariableScopeLabel = new Label();

            protected void pushLocalVariables(final DecoderTreeNodeFieldInstructionArguments arguments) {
                final int threshold = Math.max(2, (int) (arguments.totalLeafCount * THRESHOLD));
                arguments.arguments.forEach((argument, entry) -> {
                    if (entry.count >= threshold && !localVariables.containsKey(argument)) {
                        ownedLocals.put(argument, String.join("_", entry.names));
                    }
                });

                if (ownedLocals.isEmpty()) {
                    return;
                }

                for (final FieldInstructionArgument argument : ownedLocals.keySet()) {
                    // This works because while it's a map, we fill and empty it like a stack.
                    final int localIndex = LOCAL_FIRST_FIELD + localVariables.size();
                    localVariables.put(argument, localIndex);

                    generateGetField(argument);
                    methodVisitor.visitVarInsn(ISTORE, localIndex);
                }

                methodVisitor.visitLabel(beginVariableScopeLabel);
            }

            protected void popVariables() {
                for (final Map.Entry<FieldInstructionArgument, String> entry : ownedLocals.entrySet()) {
                    final FieldInstructionArgument argument = entry.getKey();
                    final int localIndex = localVariables.removeInt(argument);

                    final Label endVariableScopeLabel = new Label();
                    methodVisitor.visitLabel(endVariableScopeLabel);
                    methodVisitor.visitLocalVariable(entry.getValue(), "I", null, beginVariableScopeLabel, endVariableScopeLabel, localIndex);
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

        private static ArrayList<MaskField> computeMaskFields(int mask) {
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

        private void generateInstOffsetInc(final int value) {
            methodVisitor.visitIincInsn(LOCAL_INST_OFFSET, value);
        }

        private void generateSavePC() {
            methodVisitor.visitVarInsn(ALOAD, LOCAL_THIS);
            methodVisitor.visitVarInsn(ILOAD, LOCAL_INST_OFFSET);
            methodVisitor.visitVarInsn(ILOAD, LOCAL_TO_PC);
            methodVisitor.visitInsn(IADD);
            methodVisitor.visitFieldInsn(PUTFIELD, Type.getInternalName(R5CPUTemplate.class), "pc", "I");
        }

        private void generateGetField(final FieldInstructionArgument argument) {
            methodVisitor.visitInsn(ICONST_0);
            for (final InstructionFieldMapping mapping : argument.mappings) {
                methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
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
}
