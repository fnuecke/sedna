package li.cil.sedna.z80.exception;

/**
 * Thrown by the generated decoder when no declaration matches. The Z80 has no illegal instruction
 * trap and the declarations are total, so this surfacing at runtime is a decoder bug.
 */
public final class Z80IllegalInstructionException extends Exception {
    public Z80IllegalInstructionException() {
        super(null, null, true, false);
    }
}
