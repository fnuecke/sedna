package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.MRET;
import static org.junit.jupiter.api.Assertions.*;

public final class DoubleTrapTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long TRAP_VECTOR = Vm.RAM_START + 0x800;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
        vm.setProgramCounter(Vm.RAM_START);
    }

    @Test
    public void handlerFaultingOnFirstInstructionHalts() {
        // RAM is zeroed, so both the code at RAM_START and the trap handler are illegal instructions.
        vm.cpu().step(1000);

        assertTrue(vm.cpu().isHalted(), "CPU must halt when the trap handler traps to itself");
        assertEquals(TRAP_VECTOR, vm.programCounter());
        assertEquals(TRAP_VECTOR, vm.readCSR(R5CSR.MEPC));

        final long retired = vm.cpu().getInstructionsRetired();
        final long time = vm.cpu().getTime();
        vm.cpu().step(1000);
        assertEquals(retired, vm.cpu().getInstructionsRetired(), "halted CPU must not execute");
        assertTrue(vm.cpu().getTime() >= time + 1000, "time must advance while halted");
    }

    @Test
    public void workingHandlerDoesNotHalt() {
        vm.write(TRAP_VECTOR, MRET);
        vm.cpu().step(1000);

        assertFalse(vm.cpu().isHalted(), "a handler that returns must not halt the CPU");
    }

    @Test
    public void resetClearsHalt() {
        vm.cpu().step(1000);
        assertTrue(vm.cpu().isHalted());

        vm.cpu().reset(true, Vm.RAM_START);
        assertFalse(vm.cpu().isHalted());
    }
}
