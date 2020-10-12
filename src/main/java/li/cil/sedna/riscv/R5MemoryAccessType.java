package li.cil.sedna.riscv;

enum R5MemoryAccessType {
    LOAD(R5.PTE_R_MASK),
    STORE(R5.PTE_W_MASK),
    FETCH(R5.PTE_X_MASK),
    ;

    public final int mask;

    R5MemoryAccessType(final int mask) {
        this.mask = mask;
    }
}
