package li.cil.sedna.riscv.dbt;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.instruction.InstructionArgument;
import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDefinition;
import li.cil.sedna.instruction.InstructionType;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.R5CPUGenerator;
import li.cil.sedna.riscv.R5Instructions;
import li.cil.sedna.riscv.R5IllegalInstructionException;
import li.cil.sedna.utils.UnsafeGetter;
import org.objectweb.asm.*;
import sun.misc.Unsafe;

import javax.annotation.Nullable;

public final class Translator implements Opcodes {
    private static final Unsafe UNSAFE = UnsafeGetter.get();

    // Threshold of instructions to emit to generate class. There's a point where they
    // can be too small to justify the overhead of calling them (since they have to be
    // called via INVOKEVIRTUAL).
    private static final int MIN_INSTRUCTIONS = 2;

    private static final Type CPU_TYPE = Type.getType(R5CPUGenerator.getGeneratedClass());
    private static final Type TRACE_TYPE = Type.getType(Trace.class);
    private static final Type ILLEGAL_INSTRUCTION_EXCEPTION_TYPE = Type.getType(R5IllegalInstructionException.class);

    private static final String CPU_FIELD_NAME = "cpu";

    // First argument to the method, the reference to the CPU we're working on.
    private static final int CPU_LOCAL_INDEX = 0; // R5CPU ref, length = 1

    // On-demand updated local holding current actual PC.
    // Used to bake instOffset for pc fixup on runtime exceptions.
    private static final int PC_LOCAL_INDEX = 1; // int, length = 1

    // Store currently being executed instruction, for patching illegal instruction exceptions.
    private static final int INST_LOCAL_INDEX = 2; // int, length = 1

    // Local for holding current cycles. Saves the GETFIELD for each increment.
    private static final int MCYCLE_LOCAL_INDEX = 3; // long, length = 2

    // Temporary storage for cause exception when patching illegal instruction exceptions.
    private static final int EXCEPTION_LOCAL_INDEX = 5; // reference, length = 1

    private final TranslatorJob request;
    private final String className;
    private final MethodVisitor mv;
    private int instOffset;
    int emittedInstructions;

    private Translator(final TranslatorJob request, final String className, final MethodVisitor mv) {
        this.request = request;
        this.className = className;
        this.mv = mv;
    }

    @Nullable
    public static Trace translateTrace(final TranslatorJob request) {
        final String className = TRACE_TYPE.getInternalName() + "$" + Integer.toHexString(request.pc);

        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8,
                ACC_PUBLIC + ACC_FINAL,
                className,
                null,
                TRACE_TYPE.getInternalName(),
                null);

        cw.visitField(ACC_PRIVATE + ACC_FINAL, CPU_FIELD_NAME, CPU_TYPE.getDescriptor(), null, null);

        generateConstructor(cw, className);
        if (!generateExecuteMethod(cw, className, request)) {
            return null;
        }

        cw.visitEnd();

        return instantiateTrace(defineClass(cw.toByteArray()), request.cpu);
    }

    private static Class<Trace> defineClass(final byte[] data) {
        @SuppressWarnings("unchecked") final Class<Trace> traceClass = (Class<Trace>) UNSAFE.defineAnonymousClass(R5CPUGenerator.getGeneratedClass(), data, null);
        UNSAFE.ensureClassInitialized(traceClass);
        return traceClass;
    }

    private static Trace instantiateTrace(final Class<Trace> traceClass, final R5CPU cpu) {
        try {
            return traceClass.getDeclaredConstructor(R5CPUGenerator.getGeneratedClass()).newInstance(cpu);
        } catch (final Throwable e) {
            throw new AssertionError("Failed instantiating trace.", e);
        }
    }

    private static void generateConstructor(final ClassVisitor cv, final String className) {
        final MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, CPU_TYPE), null, null);

        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, TRACE_TYPE.getInternalName(), "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, className, CPU_FIELD_NAME, CPU_TYPE.getDescriptor());
        mv.visitInsn(RETURN);

        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }

    private static boolean generateExecuteMethod(final ClassVisitor cv, final String className, final TranslatorJob request) {
        final MethodVisitor mv = cv.visitMethod(ACC_PUBLIC + ACC_FINAL, "execute", "()V", null, new String[]{
                Type.getInternalName(R5IllegalInstructionException.class),
                Type.getInternalName(MemoryAccessException.class)
        });

        mv.visitCode();

        final Translator translator = new Translator(request, className, mv);
        translator.translateTrace();
        if (translator.emittedInstructions < MIN_INSTRUCTIONS) {
            return false;
        }

        mv.visitMaxs(-1, -1);
        mv.visitEnd();
        return true;
    }

    private void translateTrace() {
        final Label startLabel = new Label();
        final Label endExceptionsLabel = new Label();
        final Label returnLabel = new Label();
        final Label catchIllegalInstructionLabel = new Label();
        final Label catchR5AndMemoryLabel = new Label();
        final Label endLabel = new Label();

        mv.visitLocalVariable("cpu", CPU_TYPE.getDescriptor(), null, startLabel, endLabel, CPU_LOCAL_INDEX);
        mv.visitLocalVariable("currentPC", Type.INT_TYPE.getDescriptor(), null, startLabel, endLabel, PC_LOCAL_INDEX);
        mv.visitLocalVariable("inst", Type.INT_TYPE.getDescriptor(), null, startLabel, endLabel, INST_LOCAL_INDEX);
        mv.visitLocalVariable("mcycle", Type.LONG_TYPE.getDescriptor(), null, startLabel, returnLabel, MCYCLE_LOCAL_INDEX);

        mv.visitTryCatchBlock(startLabel, endExceptionsLabel, catchIllegalInstructionLabel, Type.getInternalName(R5IllegalInstructionException.class));
        mv.visitTryCatchBlock(startLabel, endExceptionsLabel, catchR5AndMemoryLabel, Type.getInternalName(R5IllegalInstructionException.class));
        mv.visitTryCatchBlock(startLabel, endExceptionsLabel, catchR5AndMemoryLabel, Type.getInternalName(MemoryAccessException.class));

        generateCPULocal();
        generatePCLocal();
        generateInstLocal();
        generateMcycleLocal();

        mv.visitLabel(startLabel);

        boolean handledException = false;
        try { // Catch illegal instruction exceptions to generate final throw instruction.
            instOffset = request.instOffset;
            final int instEnd = request.instEnd;
            int inst = request.firstInst;

            // TODO trim nops completely, i.e. anything where rd = 0 that just computes and writes to it
            // TODO test if incrementing instOffset/pc in generated method is better than generating a ton of constants

            final StringBuilder descBuilder = new StringBuilder();
            for (; ; ) { // End of page check at the bottom since we enter with a valid inst.
                emittedInstructions++;

                incCycle();

                final InstructionDeclaration declaration = R5Instructions.findDeclaration(inst);
                if (declaration == null || declaration.type == InstructionType.ILLEGAL) {
                    throw new R5IllegalInstructionException(inst);
                }

                if (declaration.type != InstructionType.HINT) {
                    final InstructionDefinition definition = R5Instructions.getDefinition(declaration);
                    if (definition == null) {
                        throw new R5IllegalInstructionException(inst);
                    }

                    if (definition.throwsException) {
                        generateSavePCInLocal();
                        generateSaveInstInLocal(inst);
                    }

//                    if (definition.readsPC) {
//                        generateSavePCInCPU();
//                    }

                    mv.visitVarInsn(ALOAD, CPU_LOCAL_INDEX);

                    descBuilder.setLength(0);
                    descBuilder.append('(');
                    for (final InstructionArgument parameter : definition.parameters) {
                        generateFastLdc(parameter.get(inst));
                        descBuilder.append('I');
                    }
                    descBuilder.append(')');

                    if (definition.returnsBoolean) {
                        descBuilder.append('Z');
                    } else {
                        descBuilder.append('V');
                    }

                    mv.visitMethodInsn(INVOKESPECIAL, CPU_TYPE.getInternalName(), definition.methodName, descBuilder.toString(), false);

                    if (definition.returnsBoolean) {
                        final Label continueLabel = new Label();
                        mv.visitJumpInsn(IFEQ, continueLabel);
                        if (!definition.writesPC) {
                            generateSavePCInCPU(declaration.size);
                        }
                        mv.visitInsn(RETURN);
                        mv.visitLabel(continueLabel);
                    } else if (definition.writesPC) {
                        return;
                    }
                }

                instOffset += declaration.size;

                if (instOffset < instEnd) { // Likely case: we're still fully in the page.
                    inst = request.device.load(instOffset, Sizes.SIZE_32_LOG2);
                } else { // Unlikely case: we reached the end of the page. Leave to do interrupts and cycle check.
                    generateSavePCInCPU();
                    return;
                }
            }
        } catch (final R5IllegalInstructionException e) {
            mv.visitLabel(endExceptionsLabel);
            handledException = true;
            generateSavePCInCPU();

            mv.visitTypeInsn(NEW, ILLEGAL_INSTRUCTION_EXCEPTION_TYPE.getInternalName());
            mv.visitInsn(DUP);
            mv.visitLdcInsn(e.getInstruction());
            mv.visitMethodInsn(INVOKESPECIAL, ILLEGAL_INSTRUCTION_EXCEPTION_TYPE.getInternalName(), "<init>", "(I)V", false);
            mv.visitInsn(ATHROW);
        } catch (final MemoryAccessException e) {
            mv.visitLabel(endExceptionsLabel);
            handledException = true;
            generateSavePCInCPU();

            final Type type = Type.getType(e.getClass());
            mv.visitTypeInsn(NEW, type.getInternalName());
            mv.visitInsn(DUP);
            mv.visitLdcInsn(e.getAddress());
            mv.visitMethodInsn(INVOKESPECIAL, type.getInternalName(), "<init>", "(I)V", false);
            mv.visitInsn(ATHROW);
        } finally {
            if (!handledException) {
                mv.visitLabel(endExceptionsLabel);
            }

            mv.visitLabel(returnLabel);
            mv.visitInsn(RETURN);

            // catch (R5IllegalInstructionException)
            mv.visitLabel(catchIllegalInstructionLabel);
            {
                generateSaveLocalPCInCPU();

                final Label beginExceptionLocal = new Label();
                mv.visitLocalVariable("e", ILLEGAL_INSTRUCTION_EXCEPTION_TYPE.getDescriptor(), null, beginExceptionLocal, endLabel, EXCEPTION_LOCAL_INDEX);
                mv.visitVarInsn(ASTORE, EXCEPTION_LOCAL_INDEX);
                mv.visitLabel(beginExceptionLocal);

                mv.visitTypeInsn(NEW, ILLEGAL_INSTRUCTION_EXCEPTION_TYPE.getInternalName());
                mv.visitInsn(DUP);
                mv.visitVarInsn(ILOAD, INST_LOCAL_INDEX);
                mv.visitVarInsn(ALOAD, EXCEPTION_LOCAL_INDEX);
                mv.visitMethodInsn(INVOKESPECIAL, ILLEGAL_INSTRUCTION_EXCEPTION_TYPE.getInternalName(), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, ILLEGAL_INSTRUCTION_EXCEPTION_TYPE), false);

                mv.visitInsn(ATHROW);
            }

            // catch (R5Exception | MemoryAccessException)
            mv.visitLabel(catchR5AndMemoryLabel);
            {
                generateSaveLocalPCInCPU();

                mv.visitInsn(ATHROW);
            }

            mv.visitLabel(endLabel);
        }
    }

    private void generateCPULocal() {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, className, CPU_FIELD_NAME, CPU_TYPE.getDescriptor());
        mv.visitVarInsn(ASTORE, CPU_LOCAL_INDEX);
    }

    private void generatePCLocal() {
        mv.visitLdcInsn(instOffset);
        mv.visitVarInsn(ISTORE, PC_LOCAL_INDEX);
    }

    private void generateInstLocal() {
        mv.visitLdcInsn(0);
        mv.visitVarInsn(ISTORE, INST_LOCAL_INDEX);
    }

    private void generateMcycleLocal() {
        // TODO Make mcycle local an int, use IINC, add to field in finally
        mv.visitVarInsn(ALOAD, CPU_LOCAL_INDEX);
        mv.visitFieldInsn(GETFIELD, CPU_TYPE.getInternalName(), "mcycle", Type.LONG_TYPE.getDescriptor());
        mv.visitVarInsn(LSTORE, MCYCLE_LOCAL_INDEX);
    }

    private void incCycle() {
        mv.visitVarInsn(ALOAD, CPU_LOCAL_INDEX);
        mv.visitVarInsn(LLOAD, MCYCLE_LOCAL_INDEX);
        mv.visitInsn(LCONST_1);
        mv.visitInsn(LADD);
        mv.visitInsn(DUP2);
        mv.visitVarInsn(LSTORE, MCYCLE_LOCAL_INDEX);
        mv.visitFieldInsn(PUTFIELD, CPU_TYPE.getInternalName(), "mcycle", Type.LONG_TYPE.getDescriptor());
    }

    private void generateSavePCInLocal() {
        mv.visitLdcInsn(instOffset + request.toPC);
        mv.visitVarInsn(ISTORE, PC_LOCAL_INDEX);
    }

    private void generateSaveInstInLocal(final int inst) {
        mv.visitLdcInsn(inst);
        mv.visitVarInsn(ISTORE, INST_LOCAL_INDEX);
    }

    private void generateSavePCInCPU() {
        generateSavePCInCPU(0);
    }

    private void generateSavePCInCPU(final int offset) {
        mv.visitVarInsn(ALOAD, CPU_LOCAL_INDEX);
        mv.visitLdcInsn(instOffset + request.toPC + offset);
        mv.visitFieldInsn(PUTFIELD, CPU_TYPE.getInternalName(), "pc", "I");
    }

    private void generateSaveLocalPCInCPU() {
        mv.visitVarInsn(ALOAD, CPU_LOCAL_INDEX);
        mv.visitVarInsn(ILOAD, PC_LOCAL_INDEX);
        mv.visitFieldInsn(PUTFIELD, CPU_TYPE.getInternalName(), "pc", Type.INT_TYPE.getDescriptor());
    }

    private void generateFastLdc(final int value) {
        if (value >= 0 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
