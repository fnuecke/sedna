package li.cil.sedna.riscv.dbt;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.riscv.R5IllegalInstructionException;

public abstract class Trace {
    public abstract void execute() throws R5IllegalInstructionException, MemoryAccessException;
}