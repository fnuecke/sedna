package li.cil.sedna.riscv.exception;

import li.cil.sedna.riscv.R5;

public final class R5ECallException extends R5Exception {
    public R5ECallException(final int privilege) {
        super(exceptionForPrivilege(privilege));
    }

    private static int exceptionForPrivilege(final int privilege) {
        /*
        switch (privilege) {
            case R5.PRIVILEGE_U: {
                return R5.EXCEPTION_USER_ECALL;
            }
            case R5.PRIVILEGE_S: {
                return R5.EXCEPTION_SUPERVISOR_ECALL;
            }
            case R5.PRIVILEGE_H: {
                return R5.EXCEPTION_HYPERVISOR_ECALL;
            }
            case R5.PRIVILEGE_M: {
                return R5.EXCEPTION_MACHINE_ECALL;
            }
        }

        Optimized:
        */
        return R5.EXCEPTION_USER_ECALL + privilege;
    }
}
