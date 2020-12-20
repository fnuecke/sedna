package li.cil.sedna.riscv.device;

import li.cil.sedna.device.syscon.AbstractSystemController;
import li.cil.sedna.riscv.exception.R5SystemPowerOffException;
import li.cil.sedna.riscv.exception.R5SystemResetException;

public class R5SystemController extends AbstractSystemController {
    @Override
    protected void handleReset() {
        throw new R5SystemResetException();
    }

    @Override
    protected void handlePowerOff() {
        throw new R5SystemPowerOffException();
    }
}
