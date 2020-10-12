package li.cil.sedna.riscv;

import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.*;
import li.cil.sedna.instruction.decoder.DecoderTreeBranchVisitor;
import li.cil.sedna.instruction.decoder.DecoderTreeLeafVisitor;
import li.cil.sedna.instruction.decoder.DecoderTreeSwitchVisitor;
import li.cil.sedna.instruction.decoder.DecoderTreeVisitor;
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
            R5Instructions.DECODER.visit(new DecoderGenerator(super.mv, continueLabel));
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
        public DecoderTreeSwitchVisitor visitSwitch(final int mask) {
            return new SwitchVisitor(mask);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch() {
            return new BranchVisitor();
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
            final int mask;
            private Label defaultCase;
            private Label[] cases;

            public SwitchVisitor(final int mask) {
                this.mask = mask;
            }

            @Override
            public void visit(final int[] patterns, final int mask) {


                boolean areCasesSequential = true;
                for (int i = 1; i < patterns.length; i++) {
                    if (patterns[i - 1] != patterns[i] - 1) {
                        areCasesSequential = false;
                        break;
                    }
                }

                defaultCase = new Label();
                cases = new Label[patterns.length];
                for (int i = 0; i < cases.length; i++) {
                    cases[i] = new Label();
                }

                methodVisitor.visitVarInsn(ILOAD, LOCAL_INST);
                methodVisitor.visitLdcInsn(mask);
                methodVisitor.visitInsn(IAND);
                if (areCasesSequential) {
                    methodVisitor.visitTableSwitchInsn(patterns[0], patterns[patterns.length - 1], defaultCase, cases);
                } else {
                    methodVisitor.visitLookupSwitchInsn(defaultCase, patterns, cases);
                }
            }

            @Override
            public DecoderTreeVisitor visitSwitchCase(final int index, final int pattern) {
                methodVisitor.visitLabel(cases[index]);
                return new SwitchCaseVisitor();
            }

            @Override
            public void visitEnd() {
                methodVisitor.visitLabel(defaultCase);
                generateIllegalInstruction();
            }
        }

        private final class SwitchCaseVisitor implements DecoderTreeVisitor {
            @Override
            public DecoderTreeSwitchVisitor visitSwitch(final int mask) {
                return new SwitchVisitor(mask);
            }

            @Override
            public DecoderTreeBranchVisitor visitBranch() {
                return new BranchVisitor();
            }

            @Override
            public DecoderTreeLeafVisitor visitInstruction() {
                return new LeafVisitor(null);
            }

            @Override
            public void visitEnd() {
                generateIllegalInstruction();
            }
        }

        private final class BranchVisitor implements DecoderTreeBranchVisitor {
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
