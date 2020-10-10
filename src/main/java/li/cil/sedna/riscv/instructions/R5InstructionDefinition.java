package li.cil.sedna.riscv.instructions;

import li.cil.sedna.riscv.R5CPU;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public final class R5InstructionDefinition {
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface Implementation {
        String value();
    }

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.CLASS)
    public @interface Field {
        String value();
    }

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.CLASS)
    public @interface InstructionSize {
    }

    private static final Logger LOGGER = LogManager.getLogger();
    private static final HashMap<R5InstructionDeclaration, R5InstructionDefinition> INSTRUCTIONS = new HashMap<>();

    public static R5InstructionDefinition get(final R5InstructionDeclaration declaration) {
        return INSTRUCTIONS.get(declaration);
    }

    public final String instructionName;
    public final String methodName;
    public final boolean readsPC;
    public final boolean writesPC;
    public final boolean returnsBoolean;
    public final R5InstructionArgument[] parameters;

    private R5InstructionDefinition(final String instructionName,
                                    final String methodName,
                                    final boolean readsPC,
                                    final boolean writesPC,
                                    final boolean returnsBoolean,
                                    final R5InstructionArgument[] parameters) {
        this.instructionName = instructionName;
        this.methodName = methodName;
        this.readsPC = readsPC;
        this.writesPC = writesPC;
        this.returnsBoolean = returnsBoolean;
        this.parameters = parameters;
    }

    private static final class InstructionFunctionVisitor extends MethodVisitor {
        private final String name;
        private final String descriptor;
        private final ParameterAnnotation[] parameterAnnotations;
        private String instructionName;
        private boolean suppressNonStaticMethodInvocationWarnings;
        private boolean readsPC;
        private boolean writesPC;

        public InstructionFunctionVisitor(final String name, final String descriptor) {
            super(Opcodes.ASM7);
            this.name = name;
            this.descriptor = descriptor;
            this.parameterAnnotations = new ParameterAnnotation[Type.getArgumentTypes(descriptor).length];
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(final int parameter, final String descriptor, final boolean visible) {
            if (Objects.equals(descriptor, Type.getDescriptor(Field.class))) {
                return new AnnotationVisitor(Opcodes.ASM7) {
                    @Override
                    public void visit(final String name, final Object value) {
                        super.visit(name, value);
                        if (Objects.equals(name, "value")) {
                            final String argumentName = (String) value;
                            if (argumentName == null) {
                                throw new IllegalStateException(String.format("Name of @Field annotation on parameter [%d] on instruction declaration [%s] is null.", parameter, InstructionFunctionVisitor.this.name));
                            }
                            parameterAnnotations[parameter] = ParameterAnnotation.createField(argumentName);
                        }
                    }
                };
            } else if (Objects.equals(descriptor, Type.getDescriptor(InstructionSize.class))) {
                parameterAnnotations[parameter] = ParameterAnnotation.createInstructionSize();
                return null;
            }

            return super.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            if (Objects.equals(descriptor, Type.getDescriptor(Implementation.class))) {
                return new AnnotationVisitor(Opcodes.ASM7) {
                    @Override
                    public void visit(final String name, final Object value) {
                        super.visit(name, value);
                        if (Objects.equals(name, "value")) {
                            instructionName = (String) value;
                        }
                    }
                };
            } else if (Objects.equals(descriptor, Type.getDescriptor(SuppressWarnings.class))) {
                return new AnnotationVisitor(Opcodes.ASM7) {
                    @Override
                    public void visit(final String name, final Object value) {
                        if (Objects.equals(name, "value") && Objects.equals(value, "ContainsNonStaticMethodInvocations")) {
                            suppressNonStaticMethodInvocationWarnings = true;
                        }
                        super.visit(name, value);
                    }
                };
            }

            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);

            if (instructionName == null) {
                return;
            }

            if (Objects.equals(owner, Type.getInternalName(R5CPU.class)) && Objects.equals(name, "pc")) {
                if (opcode == Opcodes.GETFIELD) {
                    readsPC = true;
                }
                if (opcode == Opcodes.PUTFIELD) {
                    writesPC = true;
                }
            }
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (suppressNonStaticMethodInvocationWarnings) {
                return;
            }

            if (opcode != Opcodes.INVOKESTATIC) {
                LOGGER.warn("Instruction definition [{}] calls non-static method [{}.{}]. " +
                            "Static analysis will not detect access to PC if called method operates on PC. " +
                            "Suppress this warning by adding `@SuppressWarnings(\"ContainsNonStaticMethodInvocations\")` " +
                            "to the instruction definition if the call is known to be safe.", name, owner, name);
            }
        }
    }

    static {
        final ArrayList<InstructionFunctionVisitor> visitors = new ArrayList<>();
        try {
            final ClassReader cr = new ClassReader(R5CPU.class.getName());
            cr.accept(new ClassVisitor(Opcodes.ASM7) {
                @Override
                public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
                    final InstructionFunctionVisitor visitor = new InstructionFunctionVisitor(name, descriptor);
                    visitors.add(visitor);
                    return visitor;
                }
            }, 0);
        } catch (final IOException e) {
            LOGGER.error(e);
        }

        final HashMap<String, InstructionFunctionVisitor> visitorByInstructionName = new HashMap<>();
        for (final InstructionFunctionVisitor visitor : visitors) {
            if (visitor.instructionName == null) {
                continue; // Not an instruction function.
            }

            if (visitorByInstructionName.containsKey(visitor.instructionName)) {
                LOGGER.warn("Duplicate instruction definitions for instruction [{}]. Using [{}].",
                        visitor.instructionName, visitor.name);
            } else {
                visitorByInstructionName.put(visitor.instructionName, visitor);
            }
        }

        final ArrayList<R5InstructionDeclaration> declarations = R5InstructionDeclaration.getAll();
        for (final R5InstructionDeclaration declaration : declarations) {
            final InstructionFunctionVisitor visitor = visitorByInstructionName.get(declaration.name);
            if (visitor == null) {
                LOGGER.warn("No instruction definition for instruction declaration [{}].", declaration.name);
                continue;
            }

            final Type returnType = Type.getReturnType(visitor.descriptor);
            final boolean returnsBoolean;
            if (Objects.equals(Type.BOOLEAN_TYPE, returnType)) {
                returnsBoolean = true;
            } else {
                returnsBoolean = false;
                if (!Objects.equals(Type.VOID_TYPE, returnType)) {
                    throw new IllegalStateException(String.format(
                            "Instruction definition [%s] return type is neither boolean nor void.", visitor.name));
                }
            }

            final Type[] argumentTypes = Type.getArgumentTypes(visitor.descriptor);
            for (int i = 0; i < argumentTypes.length; i++) {
                if (!Objects.equals(argumentTypes[i], Type.INT_TYPE)) {
                    throw new IllegalStateException(String.format(
                            "Instruction definition [%s] uses parameter of type [%s] but only `int` parameters are " +
                            "supported.",
                            visitor.name, argumentTypes[i].getClassName()));
                }
            }

            int argumentCount = 0;
            for (int i = 0; i < visitor.parameterAnnotations.length; i++) {
                if (visitor.parameterAnnotations[i] == null) {
                    throw new IllegalStateException(String.format(
                            "Instruction definition [%s] parameter [%d] has no usage annotation. Annotate arguments with the @Field annotations and instruction size parameters with the @InstructionSize annotation.",
                            visitor.name, i + 1));
                }
                if (visitor.parameterAnnotations[i].argumentName != null) {
                    argumentCount++;
                }
            }

            if (argumentCount != declaration.arguments.size()) {
                throw new IllegalStateException(String.format(
                        "Number of @Field parameters [%d] in instruction definition [%s] does not match number of " +
                        "expected arguments [%d] in instruction declaration of instruction [%s].",
                        argumentCount, visitor.name, declaration.arguments.size(), declaration.name));
            }

            final R5InstructionArgument[] arguments = new R5InstructionArgument[visitor.parameterAnnotations.length];
            for (int i = 0; i < visitor.parameterAnnotations.length; i++) {
                final ParameterAnnotation annotation = visitor.parameterAnnotations[i];
                final R5InstructionArgument argument;
                if (annotation.argumentName != null) {
                    final String argumentName = annotation.argumentName;
                    argument = declaration.arguments.get(argumentName);
                    if (argument == null) {
                        throw new IllegalStateException(String.format(
                                "Required argument [%s] for instruction definition [%s] not defined in instruction " +
                                "declaration.",
                                argumentName, declaration.name));
                    }
                } else if (annotation.isInstructionSize) {
                    argument = new R5ConstantInstructionArgument(declaration.size);
                } else {
                    throw new AssertionError("Annotation info was generated but for neither @Field nor @InstructionSize annotation.");
                }

                arguments[i] = argument;
            }

            final R5InstructionDefinition definition = new R5InstructionDefinition(
                    declaration.name,
                    visitor.name,
                    visitor.readsPC,
                    visitor.writesPC,
                    returnsBoolean,
                    arguments);
            INSTRUCTIONS.put(declaration, definition);
        }
    }

    private static final class ParameterAnnotation {
        public String argumentName;
        public boolean isInstructionSize;

        public static ParameterAnnotation createField(final String name) {
            final ParameterAnnotation result = new ParameterAnnotation();
            result.argumentName = name;
            return result;
        }

        public static ParameterAnnotation createInstructionSize() {
            final ParameterAnnotation result = new ParameterAnnotation();
            result.isInstructionSize = true;
            return result;
        }
    }
}
