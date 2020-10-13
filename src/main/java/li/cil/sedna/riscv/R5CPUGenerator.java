package li.cil.sedna.riscv;

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
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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
        private final MethodVisitor methodVisitor;
        private final Label continueLabel;

        private static final int LOCAL_THIS = 0;
        private static final int LOCAL_CACHE = 1;
        private static final int LOCAL_INST = 2;
        private static final int LOCAL_INST_OFFSET = 3;
        private static final int LOCAL_TO_PC = 4;
        private static final int LOCAL_INST_END = 5;

        private DecoderGenerator(final MethodVisitor methodVisitor, final Label continueLabel) {
            this.methodVisitor = methodVisitor;
            this.continueLabel = continueLabel;
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
            return new LeafVisitor(null);
        }

        @Override
        public void visitEnd() {
            generateIllegalInstruction();
        }

        private final class SwitchVisitor implements DecoderTreeSwitchVisitor {
            private final int processedMask;
            private final Label defaultCase = new Label();
            private Label[] cases;

            public SwitchVisitor(final int processedMask) {
                this.processedMask = processedMask;
            }

            @Override
            public void visit(final DecoderTreeSwitchNode node) {
                final int caseCount = node.patterns.length;

                cases = new Label[caseCount];
                for (int i = 0; i < caseCount; i++) {
                    cases[i] = new Label();
                }

                final int mask = node.mask & ~processedMask;
                final ArrayList<MaskField> maskFields = computeMaskFields(mask);

                // If we have mask fields that are the same in all patterns, generate an early-out if.
                // Not so much because of the early out, but because it allows us to simplify the switch
                // and increases the likelihood that we'll get sequential patterns.
                final ArrayList<MaskField> maskFieldsWithEqualPatterns = new ArrayList<>();
                for (int i = maskFields.size() - 1; i >= 0; i--) {
                    final int fieldMask = maskFields.get(i).asMask();
                    final int pattern = node.patterns[0] & fieldMask;

                    boolean patternsMatch = true;
                    for (int j = 1; j < caseCount; j++) {
                        if ((node.patterns[j] & fieldMask) != pattern) {
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
                            Arrays.stream(node.children).map(Object::toString).collect(Collectors.joining(", "))));
                }

                if (!maskFieldsWithEqualPatterns.isEmpty()) {
                    int commonMask = 0;
                    for (final MaskField maskField : maskFieldsWithEqualPatterns) {
                        commonMask |= maskField.asMask();
                    }
                    final int commonPattern = node.patterns[0] & commonMask;

                    final Label switchLabel = new Label();

                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                    methodVisitor.visitLdcInsn(commonMask);
                    methodVisitor.visitInsn(IAND);
                    methodVisitor.visitLdcInsn(commonPattern);
                    methodVisitor.visitJumpInsn(IF_ICMPEQ, switchLabel);
                    generateIllegalInstruction();
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
                        tablePattern |= ((node.patterns[i] & maskField.asMask()) >>> maskField.srcLSB) << offset;
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

                    // For sequential patterns we can create a table switch.
                    if (maskFields.size() == 1 && maskFields.get(0).srcLSB == 0) {
                        // Trivial case: patterns are sequential as-is.
                        for (int i = 0; i < caseCount; i++) {
                            assert (node.patterns[i] & mask) == (tablePatterns[i] & mask);
                        }
                        methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                        methodVisitor.visitLdcInsn(mask);
                        methodVisitor.visitInsn(IAND);
                    } else {
                        // General case: mask out fields from instruction and shift them down to be adjacent so the
                        // compressed instruction can match our compressed patterns.
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
                    System.arraycopy(node.patterns, 0, patterns, 0, caseCount);
                    final Label[] labels = sortPatternsAndGetRemappedLabels(patterns);

                    // For non-sequential patterns we have to create a lookup switch.
                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                    methodVisitor.visitLdcInsn(node.mask);
                    methodVisitor.visitInsn(IAND);
                    methodVisitor.visitLookupSwitchInsn(defaultCase, patterns, labels);
                }
            }

            @Override
            public DecoderTreeVisitor visitSwitchCase(final DecoderTreeSwitchNode node, final int index) {
                methodVisitor.visitLabel(cases[index]);
                return new SwitchCaseVisitor(processedMask | node.mask);
            }

            @Override
            public void visitEnd() {
                methodVisitor.visitLabel(defaultCase);
                generateIllegalInstruction();
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

        private void generatePrintln(final String s) {
            methodVisitor.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
            methodVisitor.visitLdcInsn(s);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println", "(Ljava/lang/String;)V", false);
        }

        private final class SwitchCaseVisitor implements DecoderTreeVisitor {
            private final int processedMask;

            public SwitchCaseVisitor(final int processedMask) {
                this.processedMask = processedMask;
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
                return new LeafVisitor(null);
            }

            @Override
            public void visitEnd() {
            }
        }

        private final class BranchVisitor implements DecoderTreeBranchVisitor {
            private final int processedMask;

            public BranchVisitor(final int processedMask) {
                this.processedMask = processedMask;
            }

            @Override
            public void visit(final int count) {
            }

            @Override
            public DecoderTreeLeafVisitor visitBranchCase(final int index, final int mask, final int pattern) {
                final Label elseLabel = new Label();

                if (mask != 0) {
                    methodVisitor.visitVarInsn(ILOAD, LOCAL_INST); // inst
                    methodVisitor.visitLdcInsn(mask); // inst, mask
                    methodVisitor.visitInsn(IAND); // instMasked
                    methodVisitor.visitLdcInsn(pattern); // inst, pattern
                    methodVisitor.visitJumpInsn(IF_ICMPNE, elseLabel);
                }

                return new LeafVisitor(elseLabel);
            }

            @Override
            public void visitEnd() {
                generateIllegalInstruction();
            }
        }

        private class LeafVisitor implements DecoderTreeLeafVisitor {
            private final Label endLabel;

            public LeafVisitor(@Nullable final Label endLabel) {
                this.endLabel = endLabel;
            }

            @Override
            public void visitInstruction(final InstructionDeclaration declaration) {
                if (declaration.type == InstructionType.ILLEGAL) {
                    generateIllegalInstruction();
                    return;
                }

                if (declaration.type == InstructionType.HINT) {
                    generateInstOffsetInc(declaration.size);
                    methodVisitor.visitJumpInsn(GOTO, continueLabel);
                    return;
                }

                final InstructionDefinition definition = R5Instructions.getDefinition(declaration);
                if (definition == null) {
                    generateIllegalInstruction();
                    return;
                }

                if (definition.readsPC) {
                    generateSavePC();
                }

                // TODO Will probably want to move parsing fields that are used by almost every instruction out to save code size.

                methodVisitor.visitVarInsn(ALOAD, LOCAL_THIS); // cpu
                for (final InstructionArgument argument : definition.parameters) {
                    if (argument instanceof ConstantInstructionArgument) {
                        final ConstantInstructionArgument constantArgument = (ConstantInstructionArgument) argument;
                        methodVisitor.visitLdcInsn(constantArgument.value);
                    } else if (argument instanceof FieldInstructionArgument) {
                        final FieldInstructionArgument fieldArgument = (FieldInstructionArgument) argument;
                        methodVisitor.visitInsn(ICONST_0);
                        for (final InstructionFieldMapping mapping : fieldArgument.mappings) {
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
                        switch (fieldArgument.postprocessor) {
                            case NONE:
                                break;
                            case ADD_8:
                                methodVisitor.visitLdcInsn(8);
                                methodVisitor.visitInsn(IADD);
                                break;
                            default:
                                throw new IllegalArgumentException();
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
                if (endLabel != null) {
                    methodVisitor.visitLabel(endLabel);
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

        private void generateIllegalInstruction() {
            methodVisitor.visitTypeInsn(NEW, Type.getInternalName(R5IllegalInstructionException.class));
            methodVisitor.visitInsn(DUP);
            methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(R5IllegalInstructionException.class), "<init>", "()V", false);
            methodVisitor.visitInsn(ATHROW);
        }
    }
}
