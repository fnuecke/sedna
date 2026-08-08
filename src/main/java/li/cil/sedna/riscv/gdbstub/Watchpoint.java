package li.cil.sedna.riscv.gdbstub;

import li.cil.sedna.utils.Interval;

public record Watchpoint(Interval range, boolean read, boolean write) {}
