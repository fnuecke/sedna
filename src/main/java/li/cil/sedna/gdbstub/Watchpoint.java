package li.cil.sedna.gdbstub;

import li.cil.sedna.utils.Interval;

public record Watchpoint(Interval range, boolean read, boolean write) {}
