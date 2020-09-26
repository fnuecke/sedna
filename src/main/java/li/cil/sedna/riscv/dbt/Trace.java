package li.cil.sedna.riscv.dbt;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.riscv.exception.R5Exception;

public abstract class Trace {
    public abstract void execute() throws R5Exception, MemoryAccessException;
}