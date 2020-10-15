package li.cil.sedna.instruction;

import li.cil.sedna.instruction.argument.InstructionArgument;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class InstructionDefinition {
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface Instruction {
        String value();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.CLASS)
    public @interface ContainsNonStaticMethodInvocations {
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

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.CLASS)
    public @interface ProgramCounter {
    }

    public final String instructionName;
    public final String methodName;
    public final boolean writesPC;
    public final boolean returnsBoolean;
    public final String[] thrownExceptions;
    public final InstructionArgument[] parameters;
    public final String[] parameterNames;

    InstructionDefinition(final String instructionName,
                          final String methodName,
                          final boolean writesPC,
                          final boolean returnsBoolean,
                          final String[] thrownExceptions,
                          final InstructionArgument[] parameters,
                          final String[] parameterNames) {
        this.instructionName = instructionName;
        this.methodName = methodName;
        this.writesPC = writesPC;
        this.returnsBoolean = returnsBoolean;
        this.thrownExceptions = thrownExceptions;
        this.parameters = parameters;
        this.parameterNames = parameterNames;
    }
}
