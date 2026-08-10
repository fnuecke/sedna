package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.NOP;
import static li.cil.sedna.riscv.R5Assembler.WFI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class InstructionCounterTests {
    private static final int RAM_SIZE = 64 * 1024;

    private static final long SCRATCH = Vm.RAM_START + 0x1000;

    private static final int IDLE_CYCLES = 10 * 10_000;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
        vm.fill(Vm.RAM_START, RAM_SIZE, NOP);
        vm.setScratchAddress(SCRATCH);
    }

    @Test
    public void executingInstructionsAdvancesBothCounters() {
        vm.cpu().step(1000);

        assertTrue(vm.cpu().getInstructionsRetired() > 0, "instructions must be counted");
        assertTrue(vm.cpu().getTime() > 0, "cycles must be counted");
    }

    @Test
    public void idlingDoesNotRetireInstructions() {
        vm.write(Vm.RAM_START, WFI);

        vm.cpu().step(1);

        final long retiredWhenParked = vm.cpu().getInstructionsRetired();
        final long cyclesWhenParked = vm.cpu().getTime();

        for (int i = 0; i < 100; i++) {
            vm.cpu().step(10_000);
        }

        assertEquals(retiredWhenParked, vm.cpu().getInstructionsRetired(),
            "an idle hart must not appear to retire instructions");
        assertTrue(vm.cpu().getTime() > cyclesWhenParked,
            "cycles must keep advancing while idle, since they are the guest's time source");
    }

    @Test
    public void wakingResumesRetiringInstructions() {
        // Point the trap vector at the field of NOPs, so a taken interrupt lands on valid code.
        vm.writeCSR(R5CSR.MTVEC, Vm.RAM_START + 0x2000);
        // WFI only parks the hart while no enabled interrupt is pending, and raising one only wakes
        // it if that interrupt is enabled, so mie has to be set up before the WFI executes.
        vm.writeCSR(R5CSR.MIE, R5.MSIP_MASK);

        vm.write(Vm.RAM_START, WFI);
        vm.setProgramCounter(Vm.RAM_START);

        vm.cpu().step(1);
        vm.cpu().step(10_000);
        final long retiredWhileParked = vm.cpu().getInstructionsRetired();

        vm.cpu().raiseInterrupts(R5.MSIP_MASK);
        vm.cpu().step(10_000);

        assertTrue(vm.cpu().getInstructionsRetired() > retiredWhileParked,
            "a woken hart must retire instructions again");
    }

    @Test
    public void straightLineCodeRetiresOneInstructionEach() {
        final long before = vm.cpu().getInstructionsRetired();

        vm.cpu().step(1000);

        final long executed = vm.cpu().getInstructionsRetired() - before;
        final long cycles = vm.cpu().getTime();

        assertEquals(cycles, executed, "with no traps and no idling, cycles and instructions agree");
    }

    @Test
    public void hardResetClearsBothCounters() {
        vm.cpu().step(1000);
        assertTrue(vm.cpu().getInstructionsRetired() > 0);

        vm.cpu().reset(true, Vm.RAM_START);

        assertEquals(0, vm.cpu().getInstructionsRetired());
        assertEquals(0, vm.cpu().getTime());
    }

    @Test
    public void csrsReportTheTwoCountersSeparately() {
        vm.write(Vm.RAM_START, WFI);

        vm.cpu().step(1);
        for (int i = 0; i < 10; i++) {
            vm.cpu().step(IDLE_CYCLES / 10);
        }

        final long cycle = vm.readCSR(R5CSR.MCYCLE);
        final long instret = vm.readCSR(R5CSR.MINSTRET);

        assertTrue(cycle - instret > IDLE_CYCLES / 2,
            String.format("after idling for ~%d cycles, cycles (%d) must have run far ahead of instructions retired (%d)",
                IDLE_CYCLES, cycle, instret));
    }
}
