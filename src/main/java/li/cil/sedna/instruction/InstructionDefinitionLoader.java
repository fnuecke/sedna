package li.cil.sedna.instruction;

import li.cil.sedna.instruction.argument.ConstantInstructionArgument;
import li.cil.sedna.instruction.argument.InstructionArgument;
import li.cil.sedna.instruction.argument.ProgramCounterInstructionArgument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;

public final class InstructionDefinitionLoader {
    private static final Logger LOGGER = LogManager.getLogger();

    public static HashMap<InstructionDeclaration, InstructionDefinition> load(final Class<?> implementation, final ArrayList<InstructionDeclaration> declarations) throws IOException {
        final HashMap<InstructionDeclaration, InstructionDefinition> definitions = new HashMap<>();

        final ArrayList<InstructionFunctionVisitor> visitors = new ArrayList<>();
        try (final InputStream stream = implementation.getClassLoader().getResourceAsStream(implementation.getName().replace('.', '/') + ".class")) {
            if (stream == null) {
                throw new IOException("Could not load class file for class [" + implementation + "].");
            }

            final ClassReader cr = new ClassReader(stream);
            cr.accept(new ClassVisitor(Opcodes.ASM7) {
                @Override
                public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
                    final InstructionFunctionVisitor visitor = new InstructionFunctionVisitor(implementation, name, descriptor, exceptions);
                    visitors.add(visitor);
                    return visitor;
                }
            }, 0);
        }

        visitors.removeIf(v -> !v.isImplementation);

        // Used to only having to check invoked methods once. We only expect few unique invocations,
        // so just a list we search linearly is fine.
        final ArrayList<NonStaticMethodInvocation> knownMethodInvocations = new ArrayList<>();
        final HashMap<String, InstructionFunctionVisitor> visitorByInstructionName = new HashMap<>();
        for (final InstructionFunctionVisitor visitor : visitors) {
            if (visitor.instructionName == null || visitor.instructionName.isEmpty()) {
                throw new IllegalArgumentException(String.format("Instruction definition on [%s] has no name.", visitor.name));
            }

            visitor.resolveInvokedMethods(knownMethodInvocations);

            if (visitorByInstructionName.containsKey(visitor.instructionName)) {
                LOGGER.warn("Duplicate instruction definitions for instruction [{}]. Using [{}].",
                    visitor.instructionName, visitor.name);
            } else {
                visitorByInstructionName.put(visitor.instructionName, visitor);
            }
        }

        for (final InstructionDeclaration declaration : declarations) {
            if (declaration.type == InstructionType.ILLEGAL || declaration.type == InstructionType.NOP) {
                continue;
            }

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
            for (int i = 0; i < argumentTypes.length; i++) {
                final Type requiredType;
                if (visitor.parameterAnnotations[i] != null && visitor.parameterAnnotations[i].isProgramCounter) {
                    requiredType = Type.LONG_TYPE;
                } else {
                    requiredType = Type.INT_TYPE;
                }
                if (!Objects.equals(argumentTypes[i], requiredType)) {
                    throw new IllegalArgumentException(String.format(
                        "Instruction definition [%s] parameter [%d] of type [%s], requires [%s].",
                        visitor.name, i, argumentTypes[i].getClassName(), requiredType.getClassName()));
                }
            }

            final int argumentCount = visitor.parameterAnnotations.length;
            int fieldArgumentCount = 0;
            for (int i = 0; i < argumentCount; i++) {
                if (visitor.parameterAnnotations[i] == null) {
                    throw new IllegalArgumentException(String.format(
                        "Instruction definition [%s] parameter [%d] has no usage annotation. Annotate arguments with the @Field annotations and instruction size parameters with the @InstructionSize annotation.",
                        visitor.name, i + 1));
                }
                if (visitor.parameterAnnotations[i].argumentName != null) {
                    fieldArgumentCount++;
                }
            }

            if (fieldArgumentCount != declaration.arguments.size()) {
                throw new IllegalArgumentException(String.format(
                    "Number of @Field parameters [%d] in instruction definition [%s] does not match number of " +
                        "expected arguments [%d] in instruction declaration of instruction [%s].",
                    fieldArgumentCount, visitor.name, declaration.arguments.size(), declaration.displayName));
            }

            final InstructionArgument[] arguments = new InstructionArgument[argumentCount];
            final String[] argumentNames = new String[argumentCount];
            for (int i = 0; i < argumentCount; i++) {
                final ParameterAnnotation annotation = visitor.parameterAnnotations[i];
                if (annotation.argumentName != null) {
                    final String argumentName = annotation.argumentName;
                    final InstructionArgument argument = declaration.arguments.get(argumentName);

                    if (argument == null) {
                        throw new IllegalArgumentException(String.format(
                            "Required argument [%s] for instruction definition [%s] not defined in instruction " +
                                "declaration.",
                            argumentName, declaration.displayName));
                    }

                    arguments[i] = argument;
                    argumentNames[i] = argumentName;
                } else if (annotation.isInstructionSize) {
                    arguments[i] = new ConstantInstructionArgument(declaration.size);
                } else if (annotation.isProgramCounter) {
                    arguments[i] = new ProgramCounterInstructionArgument();
                } else {
                    throw new AssertionError("Annotation info was generated but for neither @Field nor " +
                        "@InstructionSize annotation.");
                }
            }

            final InstructionDefinition definition = new InstructionDefinition(
                declaration.name,
                visitor.name,
                visitor.writesPC,
                returnsBoolean,
                visitor.thrownExceptions,
                arguments,
                argumentNames);
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
        private final ArrayList<NonStaticMethodInvocation> nonStaticMethodInvocations = new ArrayList<>();
        private boolean isImplementation;
        private String instructionName;
        private boolean writesPC;

        public InstructionFunctionVisitor(final Class<?> implementation, final String name, final String descriptor, final String[] exceptions) {
            super(Opcodes.ASM7);
            this.implementation = implementation;
            this.name = name;
            this.descriptor = descriptor;
            this.thrownExceptions = exceptions;
            this.parameterAnnotations = new ParameterAnnotation[Type.getArgumentTypes(descriptor).length];
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
                        "value will be incorrect. Use the @ProgramCounter " +
                        "annotation to have the current PC value passed " +
                        "to the instruction.", this.name));
                }
                if (opcode == Opcodes.PUTFIELD) {
                    writesPC = true;
                }
            }
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            if (!isImplementation) {
                return;
            }

            if (opcode != Opcodes.INVOKESTATIC) {
                nonStaticMethodInvocations.add(new NonStaticMethodInvocation(implementation, owner, name, descriptor));
            }
        }

        public void resolveInvokedMethods(final ArrayList<NonStaticMethodInvocation> knownMethodInvocations) throws IOException {
            if (writesPC) {
                return;
            }

            for (final NonStaticMethodInvocation invocation : nonStaticMethodInvocations) {
                if (invocation.computeWritesPC(knownMethodInvocations)) {
                    writesPC = true;
                    break;
                }
            }
        }

        @Override
        public String toString() {
            return instructionName;
        }
    }

    private static final class NonStaticMethodInvocation {
        private final Class<?> implementation;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final ArrayList<NonStaticMethodInvocation> invocations = new ArrayList<>();
        private boolean hasResolvedInvocations;
        private boolean writesPC;
        private boolean hasComputedWritesPC;

        private NonStaticMethodInvocation(final Class<?> implementation, final String owner, final String name, final String descriptor) {
            this.implementation = implementation;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final NonStaticMethodInvocation that = (NonStaticMethodInvocation) o;
            return owner.equals(that.owner) &&
                name.equals(that.name) &&
                descriptor.equals(that.descriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, descriptor);
        }

        public boolean computeWritesPC(final ArrayList<NonStaticMethodInvocation> knownMethodInvocations) throws IOException {
            final NonStaticMethodInvocation invocation = getUniqueInvocation(this, knownMethodInvocations);
            invocation.resolveInvocations(knownMethodInvocations);
            invocation.resolveWritesPC();
            return invocation.writesPC;
        }

        private static NonStaticMethodInvocation getUniqueInvocation(final NonStaticMethodInvocation invocation, final ArrayList<NonStaticMethodInvocation> knownMethodInvocations) {
            final int index = knownMethodInvocations.indexOf(invocation);
            if (index >= 0) {
                return knownMethodInvocations.get(index);
            } else {
                knownMethodInvocations.add(invocation);
                return invocation;
            }
        }

        private void resolveInvocations(final ArrayList<NonStaticMethodInvocation> knownMethodInvocations) throws IOException {
            if (hasResolvedInvocations) {
                return;
            }

            final String ownerClassName = Type.getObjectType(owner).getClassName();

            // Skip built-in stuff.
            if (ownerClassName.startsWith("java.")) {
                hasResolvedInvocations = true;
                return;
            }

            try (final InputStream stream = implementation.getClassLoader().getResourceAsStream(ownerClassName.replace('.', '/') + ".class")) {
                if (stream == null) {
                    hasResolvedInvocations = true;
                    LOGGER.warn("Failed loading class for type [{}] for analysis, skipping it.", ownerClassName);
                    return;
                }

                final ClassReader reader = new ClassReader(stream);
                reader.accept(new ClassVisitor(Opcodes.ASM7) {
                    @Override
                    public MethodVisitor visitMethod(final int access, final String methodName, final String methodDescriptor, final String signature, final String[] exceptions) {
                        if (methodName.equals(NonStaticMethodInvocation.this.name) &&
                            methodDescriptor.equals(NonStaticMethodInvocation.this.descriptor)) {
                            return new MethodVisitor(Opcodes.ASM7) {
                                @Override
                                public void visitMethodInsn(final int opcode, final String invokedMethodOwner, final String invokedMethodName, final String invokedMethodDescriptor, final boolean isInterface) {
                                    super.visitMethodInsn(opcode, invokedMethodOwner, invokedMethodName, invokedMethodDescriptor, isInterface);

                                    if (opcode != Opcodes.INVOKESTATIC) {
                                        final NonStaticMethodInvocation invocation = new NonStaticMethodInvocation(implementation, invokedMethodOwner, invokedMethodName, invokedMethodDescriptor);
                                        invocations.add(getUniqueInvocation(invocation, knownMethodInvocations));
                                    }
                                }

                                @Override
                                public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
                                    super.visitFieldInsn(opcode, owner, name, descriptor);
                                    if (Objects.equals(owner, Type.getInternalName(implementation)) && Objects.equals(name, "pc")) {
                                        if (opcode == Opcodes.GETFIELD) {
                                            throw new IllegalArgumentException(
                                                String.format("Method [%s] which is invoked by an instruction is " +
                                                    "reading from PC field. This value will be incorrect. " +
                                                    "Use the @ProgramCounter annotation to have the current " +
                                                    "PC value passed to the instruction and pass it along.", methodName));
                                        }
                                        if (opcode == Opcodes.PUTFIELD) {
                                            writesPC = true;
                                        }
                                    }
                                }
                            };
                        } else {
                            return super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);
                        }
                    }
                }, 0);
            }

            hasResolvedInvocations = true;

            for (final NonStaticMethodInvocation invocation : invocations) {
                invocation.resolveInvocations(knownMethodInvocations);
            }
        }

        private void resolveWritesPC() {
            if (hasComputedWritesPC) {
                return;
            }

            propagateWrites(new HashSet<>());

            hasComputedWritesPC = true;
        }

        private boolean propagateWrites(final HashSet<NonStaticMethodInvocation> seen) {
            if (writesPC) {
                return false;
            }

            for (; ; ) {
                boolean didAnyChange = false;
                for (final NonStaticMethodInvocation invocation : invocations) {
                    if (seen.addAll(invocations)) {
                        final boolean didChange = invocation.propagateWrites(seen);
                        didAnyChange = didAnyChange || didChange;
                    }
                }

                if (!didAnyChange) {
                    break;
                }
            }

            for (final NonStaticMethodInvocation invocation : invocations) {
                writesPC = writesPC || invocation.writesPC;
            }

            return writesPC;
        }

        @Override
        public String toString() {
            return name;
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
