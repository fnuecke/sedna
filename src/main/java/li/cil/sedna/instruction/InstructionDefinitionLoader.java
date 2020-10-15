package li.cil.sedna.instruction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public final class InstructionDefinitionLoader {
    private static final Logger LOGGER = LogManager.getLogger();

    public static HashMap<InstructionDeclaration, InstructionDefinition> load(final Class<?> implementation, final ArrayList<InstructionDeclaration> declarations) throws IOException {
        final HashMap<InstructionDeclaration, InstructionDefinition> definitions = new HashMap<>();

        final ArrayList<InstructionFunctionVisitor> visitors = new ArrayList<>();
        final ClassReader cr = new ClassReader(implementation.getName());
        cr.accept(new ClassVisitor(Opcodes.ASM7) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
                final InstructionFunctionVisitor visitor = new InstructionFunctionVisitor(implementation, name, descriptor, exceptions);
                visitors.add(visitor);
                return visitor;
            }
        }, 0);

        final HashMap<String, InstructionFunctionVisitor> visitorByInstructionName = new HashMap<>();
        for (final InstructionFunctionVisitor visitor : visitors) {
            if (!visitor.isImplementation) {
                continue;
            }

            if (visitor.instructionName == null || visitor.instructionName.isEmpty()) {
                throw new IllegalArgumentException(String.format("Instruction definition on [%s] has no name.", visitor.name));
            }

            if (visitorByInstructionName.containsKey(visitor.instructionName)) {
                LOGGER.warn("Duplicate instruction definitions for instruction [{}]. Using [{}].",
                        visitor.instructionName, visitor.name);
            } else {
                visitorByInstructionName.put(visitor.instructionName, visitor);
            }
        }

        for (final InstructionDeclaration declaration : declarations) {
            final InstructionFunctionVisitor visitor = visitorByInstructionName.get(declaration.name);
            if (visitor == null) {
                LOGGER.warn("No instruction definition for instruction declaration [{}].", declaration.displayName);
                continue;
            }

            final Type returnType = Type.getReturnType(visitor.descriptor);
            final boolean returnsBoolean;
            if (Objects.equals(Type.BOOLEAN_TYPE, returnType)) {
                returnsBoolean = true;
            } else {
                returnsBoolean = false;
                if (!Objects.equals(Type.VOID_TYPE, returnType)) {
                    throw new IllegalArgumentException(String.format(
                            "Instruction definition [%s] return type is neither boolean nor void.", visitor.name));
                }
            }

            final Type[] argumentTypes = Type.getArgumentTypes(visitor.descriptor);
            for (final Type argumentType : argumentTypes) {
                if (!Objects.equals(argumentType, Type.INT_TYPE)) {
                    throw new IllegalArgumentException(String.format(
                            "Instruction definition [%s] uses parameter of type [%s] but only `int` parameters are " +
                            "supported.",
                            visitor.name, argumentType.getClassName()));
                }
            }

            int argumentCount = 0;
            for (int i = 0; i < visitor.parameterAnnotations.length; i++) {
                if (visitor.parameterAnnotations[i] == null) {
                    throw new IllegalArgumentException(String.format(
                            "Instruction definition [%s] parameter [%d] has no usage annotation. Annotate arguments with the @Field annotations and instruction size parameters with the @InstructionSize annotation.",
                            visitor.name, i + 1));
                }
                if (visitor.parameterAnnotations[i].argumentName != null) {
                    argumentCount++;
                }
            }

            if (argumentCount != declaration.arguments.size()) {
                throw new IllegalArgumentException(String.format(
                        "Number of @Field parameters [%d] in instruction definition [%s] does not match number of " +
                        "expected arguments [%d] in instruction declaration of instruction [%s].",
                        argumentCount, visitor.name, declaration.arguments.size(), declaration.displayName));
            }

            final InstructionArgument[] arguments = new InstructionArgument[visitor.parameterAnnotations.length];
            for (int i = 0; i < visitor.parameterAnnotations.length; i++) {
                final ParameterAnnotation annotation = visitor.parameterAnnotations[i];
                final InstructionArgument argument;
                if (annotation.argumentName != null) {
                    final String argumentName = annotation.argumentName;
                    argument = declaration.arguments.get(argumentName);
                    if (argument == null) {
                        throw new IllegalArgumentException(String.format(
                                "Required argument [%s] for instruction definition [%s] not defined in instruction " +
                                "declaration.",
                                argumentName, declaration.displayName));
                    }
                } else if (annotation.isInstructionSize) {
                    argument = new ConstantInstructionArgument(declaration.size);
                } else if (annotation.isProgramCounter) {
                    argument = new ProgramCounterInstructionArgument();
                } else {
                    throw new AssertionError("Annotation info was generated but for neither @Field nor @InstructionSize annotation.");
                }

                arguments[i] = argument;
            }

            final InstructionDefinition definition = new InstructionDefinition(
                    declaration.name,
                    visitor.name,
                    visitor.writesPC,
                    returnsBoolean,
                    visitor.thrownExceptions,
                    arguments);
            definitions.put(declaration, definition);
        }

        return definitions;
    }

    private static final class InstructionFunctionVisitor extends MethodVisitor {
        private final Class<?> implementation;
        private final String name;
        private final String descriptor;
        private final String[] thrownExceptions;
        private final ParameterAnnotation[] parameterAnnotations;
        private boolean isImplementation;
        private String instructionName;
        private boolean suppressNonStaticMethodInvocationWarnings;
        private boolean writesPC;

        public InstructionFunctionVisitor(final Class<?> implementation, final String name, final String descriptor, final String[] exceptions) {
            super(Opcodes.ASM7);
            this.implementation = implementation;
            this.name = name;
            this.descriptor = descriptor;
            this.parameterAnnotations = new ParameterAnnotation[Type.getArgumentTypes(descriptor).length];
            this.thrownExceptions = exceptions;
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(final int parameter, final String descriptor, final boolean visible) {
            if (Objects.equals(descriptor, Type.getDescriptor(InstructionDefinition.Field.class))) {
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
            } else if (Objects.equals(descriptor, Type.getDescriptor(InstructionDefinition.InstructionSize.class))) {
                parameterAnnotations[parameter] = ParameterAnnotation.createInstructionSize();
                return null;
            } else if (Objects.equals(descriptor, Type.getDescriptor(InstructionDefinition.ProgramCounter.class))) {
                parameterAnnotations[parameter] = ParameterAnnotation.createProgramCounter();
                return null;
            }

            return super.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            if (Objects.equals(descriptor, Type.getDescriptor(InstructionDefinition.Instruction.class))) {
                isImplementation = true;
                return new AnnotationVisitor(Opcodes.ASM7) {
                    @Override
                    public void visit(final String name, final Object value) {
                        super.visit(name, value);
                        if (Objects.equals(name, "value")) {
                            instructionName = (String) value;
                        }
                    }
                };
            } else if (Objects.equals(descriptor, Type.getDescriptor(InstructionDefinition.ContainsNonStaticMethodInvocations.class))) {
                suppressNonStaticMethodInvocationWarnings = true;
            }

            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);

            if (!isImplementation) {
                return;
            }

            if (Objects.equals(owner, Type.getInternalName(implementation)) && Objects.equals(name, "pc")) {
                if (opcode == Opcodes.GETFIELD) {
                    throw new IllegalArgumentException(String.format("Instruction [%s] is reading from PC field. This " +
                                                                     "value will most likely be incorrect. Use the " +
                                                                     "`@ProgramCounter` annotation to have the current " +
                                                                     "PC value passed to the instruction.", this.name));
                }
                if (opcode == Opcodes.PUTFIELD) {
                    writesPC = true;
                }
            }
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!isImplementation || suppressNonStaticMethodInvocationWarnings) {
                return;
            }

            if (opcode != Opcodes.INVOKESTATIC) {
                throw new IllegalArgumentException(String.format("Instruction definition [%s] calls non-static method [%s.%s]. " +
                                                                 "Static analysis will not detect access to PC if called method operates on PC. " +
                                                                 "Suppress this error by adding `@ContainsNonStaticMethodInvocations` to the " +
                                                                 "instruction definition if the call is known to be safe.", this.name, owner, name));
            }
        }
    }

    private static final class ParameterAnnotation {
        public String argumentName;
        public boolean isInstructionSize;
        public boolean isProgramCounter;

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

        public static ParameterAnnotation createProgramCounter() {
            final ParameterAnnotation result = new ParameterAnnotation();
            result.isProgramCounter = true;
            return result;
        }
    }
}
