package li.cil.sedna.riscv.exception;

public final class R5MemoryAccessException extends Exception {
    private final long address;
    private final int type;

    public R5MemoryAccessException(final long address, final int type) {
        // This exception is for control-flow, so it's fired a lot; skip stacktrace.
        super(null, null, false, false);
        this.address = address;
        this.type = type;
    }

    public long getAddress() {
        return address;
    }

    public int getType() {
        return type;
    }
}
