package li.cil.sedna.vm.riscv.dbt;

import li.cil.sedna.api.vm.device.memory.MemoryAccessException;
import li.cil.sedna.vm.riscv.exception.R5Exception;

public abstract class Trace {
    public abstract void execute() throws R5Exception, MemoryAccessException;
}