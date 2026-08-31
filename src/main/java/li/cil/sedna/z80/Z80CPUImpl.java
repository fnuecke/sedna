/*
 * This file is GENERATED - do not edit it by hand; any changes will be overwritten.
 * Regenerate with `./gradlew generateZ80Decoder`, which runs li.cil.sedna.z80.Z80CPUImplGenerator.
 */

package li.cil.sedna.z80;

import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.utils.BitUtils;
import li.cil.sedna.z80.exception.Z80IllegalInstructionException;

final class Z80CPUImpl extends Z80CPUBase {
    Z80CPUImpl(final MemoryMap memoryMap, final MemoryMap ioMap) {
        super(memoryMap, ioMap);
    }

    @Override
    protected void interpretTrace(final boolean singleStep, final LongSet breakpoints) {
        long pc = this.pc & 0xFFFF;
        int instOffset = (int) pc;
        try {
            for (; ; ) {
                if (breakpoints != null && breakpoints.contains(pc)) {
                    this.pc = pc;
                    debugInterface.handleBreakpoint(pc);
                    return;
                }

                final int inst = fetch32((int) pc);
                bumpR(inst);

                decode: {
                    switch (((inst & 0xc0) >>> 6)) {
                        case 0: {
                            switch (inst & 0x7) {
                                case 0: {
                                    switch (inst & 0xe7) {
                                        case 0: {
                                            switch (((inst & 0x18) >>> 3)) {
                                                case 0: {
                                                    nop();
                                                    pc += 1;
                                                    instOffset += 1;
                                                    break decode;
                                                }
                                                case 1: {
                                                    ex_af_af2();
                                                    pc += 1;
                                                    instOffset += 1;
                                                    break decode;
                                                }
                                                case 2: {
                                                    if (djnz(BitUtils.extendSign(((inst >>> 8) & 0xff), 8), pc, 2)) {
                                                        pc = this.pc & 0xFFFF;
                                                        instOffset = (int) pc;
                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                            return;
                                                        }
                                                        break decode;
                                                    }
                                                    pc += 2;
                                                    instOffset += 2;
                                                    break decode;
                                                }
                                                case 3: {
                                                    jr(BitUtils.extendSign(((inst >>> 8) & 0xff), 8), pc, 2);
                                                    pc = this.pc & 0xFFFF;
                                                    instOffset = (int) pc;
                                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                        return;
                                                    }
                                                    break decode;
                                                }
                                                default:
                                                    throw illegalInstruction();
                                            }
                                        }
                                        case 32: {
                                            if (jr_cc(((inst >>> 3) & 0x3), BitUtils.extendSign(((inst >>> 8) & 0xff), 8), pc, 2)) {
                                                pc = this.pc & 0xFFFF;
                                                instOffset = (int) pc;
                                                if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                    return;
                                                }
                                                break decode;
                                            }
                                            pc += 2;
                                            instOffset += 2;
                                            break decode;
                                        }
                                        default:
                                            throw illegalInstruction();
                                    }
                                }
                                case 1: {
                                    final int rp = ((inst >>> 4) & 0x3);
                                    if ((inst & 0x8) == 0x0) {
                                        ld_rp_nn(rp, ((inst >>> 8) & 0xffff));
                                        pc += 3;
                                        instOffset += 3;
                                        break decode;
                                    }
                                    if ((inst & 0x8) == 0x8) {
                                        add_hl_rp(rp);
                                        pc += 1;
                                        instOffset += 1;
                                        break decode;
                                    }
                                    throw illegalInstruction();
                                }
                                case 2: {
                                    switch (inst & 0xef) {
                                        case 2: {
                                            ld_bcde_a(((inst >>> 4) & 0x1));
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 10: {
                                            ld_a_bcde(((inst >>> 4) & 0x1));
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 34: {
                                            interpretTrace$instructionGroup0(inst, pc);
                                            pc += 3;
                                            instOffset += 3;
                                            break decode;
                                        }
                                        case 42: {
                                            interpretTrace$instructionGroup1(inst, pc);
                                            pc += 3;
                                            instOffset += 3;
                                            break decode;
                                        }
                                        default:
                                            throw illegalInstruction();
                                    }
                                }
                                case 3: {
                                    interpretTrace$instructionGroup2(inst, pc);
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                case 4: {
                                    interpretTrace$instructionGroup3(inst, pc);
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                case 5: {
                                    interpretTrace$instructionGroup4(inst, pc);
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                case 6: {
                                    interpretTrace$instructionGroup5(inst, pc);
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 7: {
                                    interpretTrace$instructionGroup6(inst, pc);
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                default:
                                    throw illegalInstruction();
                            }
                        }
                        case 1: {
                            switch (interpretTrace$instructionGroup7(inst, pc)) {
                                case 0 -> {
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 1;
                                    instOffset += 1;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    pc = this.pc & 0xFFFF;
                                    instOffset = (int) pc;
                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                        return;
                                    }
                                    break decode;
                                }
                                case 4 -> {
                                    pc = this.pc & 0xFFFF;
                                    instOffset = (int) pc;
                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                        return;
                                    }
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        case 2: {
                            interpretTrace$instructionGroup8(inst, pc);
                            pc += 1;
                            instOffset += 1;
                            break decode;
                        }
                        case 3: {
                            switch (inst & 0x7) {
                                case 0: {
                                    if (ret_cc(((inst >>> 3) & 0x7))) {
                                        pc = this.pc & 0xFFFF;
                                        instOffset = (int) pc;
                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                            return;
                                        }
                                        break decode;
                                    }
                                    pc += 1;
                                    instOffset += 1;
                                    break decode;
                                }
                                case 1: {
                                    switch (interpretTrace$instructionGroup9(inst, pc)) {
                                        case 0 -> {
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 1 -> {
                                            pc += 1;
                                            instOffset += 1;
                                            this.pc = pc;
                                            return;
                                        }
                                        case 2 -> {
                                            return;
                                        }
                                        case 3 -> {
                                            pc = this.pc & 0xFFFF;
                                            instOffset = (int) pc;
                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                return;
                                            }
                                            break decode;
                                        }
                                        case 4 -> {
                                            pc = this.pc & 0xFFFF;
                                            instOffset = (int) pc;
                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                return;
                                            }
                                            break decode;
                                        }
                                        default -> throw illegalInstruction();
                                    }
                                }
                                case 2: {
                                    if (jp_cc(((inst >>> 3) & 0x7), ((inst >>> 8) & 0xffff))) {
                                        pc = this.pc & 0xFFFF;
                                        instOffset = (int) pc;
                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                            return;
                                        }
                                        break decode;
                                    }
                                    pc += 3;
                                    instOffset += 3;
                                    break decode;
                                }
                                case 3: {
                                    switch (((inst & 0x38) >>> 3)) {
                                        case 0: {
                                            jp_nn(((inst >>> 8) & 0xffff));
                                            pc = this.pc & 0xFFFF;
                                            instOffset = (int) pc;
                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                return;
                                            }
                                            break decode;
                                        }
                                        case 1: {
                                            interpretTrace$instructionGroup11(inst, pc);
                                            pc += 2;
                                            instOffset += 2;
                                            break decode;
                                        }
                                        case 2: {
                                            out_n_a(((inst >>> 8) & 0xff));
                                            pc += 2;
                                            instOffset += 2;
                                            break decode;
                                        }
                                        case 3: {
                                            in_a_n(((inst >>> 8) & 0xff));
                                            pc += 2;
                                            instOffset += 2;
                                            break decode;
                                        }
                                        case 4: {
                                            ex_sp_hl();
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 5: {
                                            ex_de_hl();
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 6: {
                                            di();
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 7: {
                                            if (ei()) {
                                                pc += 1;
                                                instOffset += 1;
                                                this.pc = pc;
                                                return;
                                            }
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        default:
                                            throw illegalInstruction();
                                    }
                                }
                                case 4: {
                                    if (call_cc(((inst >>> 3) & 0x7), ((inst >>> 8) & 0xffff), pc, 3)) {
                                        pc = this.pc & 0xFFFF;
                                        instOffset = (int) pc;
                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                            return;
                                        }
                                        break decode;
                                    }
                                    pc += 3;
                                    instOffset += 3;
                                    break decode;
                                }
                                case 5: {
                                    switch (inst & 0xcf) {
                                        case 197: {
                                            push_rp2(((inst >>> 4) & 0x3));
                                            pc += 1;
                                            instOffset += 1;
                                            break decode;
                                        }
                                        case 205: {
                                            switch (inst & 0xdf) {
                                                case 205: {
                                                    switch (inst & 0xff) {
                                                        case 205: {
                                                            call_nn(((inst >>> 8) & 0xffff), pc, 3);
                                                            pc = this.pc & 0xFFFF;
                                                            instOffset = (int) pc;
                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                return;
                                                            }
                                                            break decode;
                                                        }
                                                        case 237: {
                                                            switch (((inst & 0xc000) >>> 14)) {
                                                                case 0: {
                                                                    ed_noni();
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                case 1: {
                                                                    switch (((inst & 0x700) >>> 8)) {
                                                                        case 0: {
                                                                            in_r_c(((inst >>> 11) & 0x7));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 1: {
                                                                            out_c_r(((inst >>> 11) & 0x7));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 2: {
                                                                            interpretTrace$instructionGroup16(inst, pc);
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 3: {
                                                                            interpretTrace$instructionGroup17(inst, pc);
                                                                            pc += 4;
                                                                            instOffset += 4;
                                                                            break decode;
                                                                        }
                                                                        case 4: {
                                                                            neg();
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 5: {
                                                                            reti_retn();
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        case 6: {
                                                                            im_y(((inst >>> 11) & 0x7));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 7: {
                                                                            interpretTrace$instructionGroup18(inst, pc);
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        default:
                                                                            throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 2: {
                                                                    switch (interpretTrace$instructionGroup22(inst, pc)) {
                                                                        case 0 -> {
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 1 -> {
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            this.pc = pc;
                                                                            return;
                                                                        }
                                                                        case 2 -> {
                                                                            return;
                                                                        }
                                                                        case 3 -> {
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        case 4 -> {
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        default -> throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 3: {
                                                                    ed_noni();
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                default:
                                                                    throw illegalInstruction();
                                                            }
                                                        }
                                                        default:
                                                            throw illegalInstruction();
                                                    }
                                                }
                                                case 221: {
                                                    switch (((inst & 0xc000) >>> 14)) {
                                                        case 0: {
                                                            switch (((inst & 0x700) >>> 8)) {
                                                                case 0: {
                                                                    switch (inst & 0xe7df) {
                                                                        case 221: {
                                                                            switch (((inst & 0x1800) >>> 11)) {
                                                                                case 0: {
                                                                                    nop();
                                                                                    pc += 2;
                                                                                    instOffset += 2;
                                                                                    break decode;
                                                                                }
                                                                                case 1: {
                                                                                    ex_af_af2();
                                                                                    pc += 2;
                                                                                    instOffset += 2;
                                                                                    break decode;
                                                                                }
                                                                                case 2: {
                                                                                    if (djnz(BitUtils.extendSign(((inst >>> 16) & 0xff), 8), pc, 3)) {
                                                                                        pc = this.pc & 0xFFFF;
                                                                                        instOffset = (int) pc;
                                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                            return;
                                                                                        }
                                                                                        break decode;
                                                                                    }
                                                                                    pc += 3;
                                                                                    instOffset += 3;
                                                                                    break decode;
                                                                                }
                                                                                case 3: {
                                                                                    jr(BitUtils.extendSign(((inst >>> 16) & 0xff), 8), pc, 3);
                                                                                    pc = this.pc & 0xFFFF;
                                                                                    instOffset = (int) pc;
                                                                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                        return;
                                                                                    }
                                                                                    break decode;
                                                                                }
                                                                                default:
                                                                                    throw illegalInstruction();
                                                                            }
                                                                        }
                                                                        case 8413: {
                                                                            if (jr_cc(((inst >>> 11) & 0x3), BitUtils.extendSign(((inst >>> 16) & 0xff), 8), pc, 3)) {
                                                                                pc = this.pc & 0xFFFF;
                                                                                instOffset = (int) pc;
                                                                                if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                    return;
                                                                                }
                                                                                break decode;
                                                                            }
                                                                            pc += 3;
                                                                            instOffset += 3;
                                                                            break decode;
                                                                        }
                                                                        default:
                                                                            throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 1: {
                                                                    final int nn = ((inst >>> 16) & 0xffff);
                                                                    final int rp = ((inst >>> 12) & 0x3);
                                                                    final int xy = ((inst >>> 5) & 0x1);
                                                                    if ((inst & 0x800) == 0x0) {
                                                                        interpretTrace$instructionGroup25(inst, pc, nn, rp, xy);
                                                                        pc += 4;
                                                                        instOffset += 4;
                                                                        break decode;
                                                                    }
                                                                    if ((inst & 0x800) == 0x800) {
                                                                        add_xy_rp(xy, rp);
                                                                        pc += 2;
                                                                        instOffset += 2;
                                                                        break decode;
                                                                    }
                                                                    throw illegalInstruction();
                                                                }
                                                                case 2: {
                                                                    switch (inst & 0xefdf) {
                                                                        case 733: {
                                                                            ld_bcde_a(((inst >>> 12) & 0x1));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 2781: {
                                                                            ld_a_bcde(((inst >>> 12) & 0x1));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 8925: {
                                                                            interpretTrace$instructionGroup26(inst, pc);
                                                                            pc += 4;
                                                                            instOffset += 4;
                                                                            break decode;
                                                                        }
                                                                        case 10973: {
                                                                            interpretTrace$instructionGroup27(inst, pc);
                                                                            pc += 4;
                                                                            instOffset += 4;
                                                                            break decode;
                                                                        }
                                                                        default:
                                                                            throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 3: {
                                                                    interpretTrace$instructionGroup28(inst, pc);
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                case 4: {
                                                                    final int xy = ((inst >>> 5) & 0x1);
                                                                    if ((inst & 0x3800) == 0x3000) {
                                                                        inc_xyd(xy, BitUtils.extendSign(((inst >>> 16) & 0xff), 8));
                                                                        pc += 3;
                                                                        instOffset += 3;
                                                                        break decode;
                                                                    }
                                                                    if ((inst & 0x3000) == 0x2000) {
                                                                        inc_xy8(xy, ((inst >>> 11) & 0x1));
                                                                        pc += 2;
                                                                        instOffset += 2;
                                                                        break decode;
                                                                    }
                                                                    inc_r(((inst >>> 11) & 0x7));
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                case 5: {
                                                                    final int xy = ((inst >>> 5) & 0x1);
                                                                    if ((inst & 0x3800) == 0x3000) {
                                                                        dec_xyd(xy, BitUtils.extendSign(((inst >>> 16) & 0xff), 8));
                                                                        pc += 3;
                                                                        instOffset += 3;
                                                                        break decode;
                                                                    }
                                                                    if ((inst & 0x3000) == 0x2000) {
                                                                        dec_xy8(xy, ((inst >>> 11) & 0x1));
                                                                        pc += 2;
                                                                        instOffset += 2;
                                                                        break decode;
                                                                    }
                                                                    dec_r(((inst >>> 11) & 0x7));
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                case 6: {
                                                                    final int n = ((inst >>> 16) & 0xff);
                                                                    final int xy = ((inst >>> 5) & 0x1);
                                                                    if ((inst & 0x3800) == 0x3000) {
                                                                        ld_xyd_n(xy, BitUtils.extendSign(((inst >>> 16) & 0xff), 8), ((inst >>> 24) & 0xff));
                                                                        pc += 4;
                                                                        instOffset += 4;
                                                                        break decode;
                                                                    }
                                                                    if ((inst & 0x3000) == 0x2000) {
                                                                        ld_xy8_n(xy, ((inst >>> 11) & 0x1), n);
                                                                        pc += 3;
                                                                        instOffset += 3;
                                                                        break decode;
                                                                    }
                                                                    ld_r_n(((inst >>> 11) & 0x7), n);
                                                                    pc += 3;
                                                                    instOffset += 3;
                                                                    break decode;
                                                                }
                                                                case 7: {
                                                                    interpretTrace$instructionGroup31(inst, pc);
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                default:
                                                                    throw illegalInstruction();
                                                            }
                                                        }
                                                        case 1: {
                                                            final int xy = ((inst >>> 5) & 0x1);
                                                            if ((inst & 0x3f00) == 0x3600) {
                                                                if (xy_halt()) {
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    this.pc = pc;
                                                                    return;
                                                                }
                                                                pc += 2;
                                                                instOffset += 2;
                                                                break decode;
                                                            }
                                                            if ((inst & 0x700) == 0x600) {
                                                                ld_r_xyd(xy, ((inst >>> 11) & 0x7), BitUtils.extendSign(((inst >>> 16) & 0xff), 8));
                                                                pc += 3;
                                                                instOffset += 3;
                                                                break decode;
                                                            }
                                                            if ((inst & 0x3800) == 0x3000) {
                                                                ld_xyd_r(xy, ((inst >>> 8) & 0x7), BitUtils.extendSign(((inst >>> 16) & 0xff), 8));
                                                                pc += 3;
                                                                instOffset += 3;
                                                                break decode;
                                                            }
                                                            ld_r_r_xy(xy, ((inst >>> 11) & 0x7), ((inst >>> 8) & 0x7));
                                                            pc += 2;
                                                            instOffset += 2;
                                                            break decode;
                                                        }
                                                        case 2: {
                                                            final int alu = ((inst >>> 11) & 0x7);
                                                            final int xy = ((inst >>> 5) & 0x1);
                                                            if ((inst & 0x700) == 0x600) {
                                                                alu_a_xyd(xy, alu, BitUtils.extendSign(((inst >>> 16) & 0xff), 8));
                                                                pc += 3;
                                                                instOffset += 3;
                                                                break decode;
                                                            }
                                                            alu_a_xy8(xy, alu, ((inst >>> 8) & 0x7));
                                                            pc += 2;
                                                            instOffset += 2;
                                                            break decode;
                                                        }
                                                        case 3: {
                                                            switch (((inst & 0x700) >>> 8)) {
                                                                case 0: {
                                                                    if (ret_cc(((inst >>> 11) & 0x7))) {
                                                                        pc = this.pc & 0xFFFF;
                                                                        instOffset = (int) pc;
                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                            return;
                                                                        }
                                                                        break decode;
                                                                    }
                                                                    pc += 2;
                                                                    instOffset += 2;
                                                                    break decode;
                                                                }
                                                                case 1: {
                                                                    switch (interpretTrace$instructionGroup32(inst, pc)) {
                                                                        case 0 -> {
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 1 -> {
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            this.pc = pc;
                                                                            return;
                                                                        }
                                                                        case 2 -> {
                                                                            return;
                                                                        }
                                                                        case 3 -> {
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        case 4 -> {
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        default -> throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 2: {
                                                                    if (jp_cc(((inst >>> 11) & 0x7), ((inst >>> 16) & 0xffff))) {
                                                                        pc = this.pc & 0xFFFF;
                                                                        instOffset = (int) pc;
                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                            return;
                                                                        }
                                                                        break decode;
                                                                    }
                                                                    pc += 4;
                                                                    instOffset += 4;
                                                                    break decode;
                                                                }
                                                                case 3: {
                                                                    switch (((inst & 0x3800) >>> 11)) {
                                                                        case 0: {
                                                                            jp_nn(((inst >>> 16) & 0xffff));
                                                                            pc = this.pc & 0xFFFF;
                                                                            instOffset = (int) pc;
                                                                            if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                return;
                                                                            }
                                                                            break decode;
                                                                        }
                                                                        case 1: {
                                                                            interpretTrace$instructionGroup35(inst, pc);
                                                                            pc += 4;
                                                                            instOffset += 4;
                                                                            break decode;
                                                                        }
                                                                        case 2: {
                                                                            out_n_a(((inst >>> 16) & 0xff));
                                                                            pc += 3;
                                                                            instOffset += 3;
                                                                            break decode;
                                                                        }
                                                                        case 3: {
                                                                            in_a_n(((inst >>> 16) & 0xff));
                                                                            pc += 3;
                                                                            instOffset += 3;
                                                                            break decode;
                                                                        }
                                                                        case 4: {
                                                                            ex_sp_xy(((inst >>> 5) & 0x1));
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 5: {
                                                                            ex_de_hl();
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 6: {
                                                                            di();
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 7: {
                                                                            if (ei()) {
                                                                                pc += 2;
                                                                                instOffset += 2;
                                                                                this.pc = pc;
                                                                                return;
                                                                            }
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        default:
                                                                            throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 4: {
                                                                    if (call_cc(((inst >>> 11) & 0x7), ((inst >>> 16) & 0xffff), pc, 4)) {
                                                                        pc = this.pc & 0xFFFF;
                                                                        instOffset = (int) pc;
                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                            return;
                                                                        }
                                                                        break decode;
                                                                    }
                                                                    pc += 4;
                                                                    instOffset += 4;
                                                                    break decode;
                                                                }
                                                                case 5: {
                                                                    switch (inst & 0xcfdf) {
                                                                        case 50653: {
                                                                            interpretTrace$instructionGroup36(inst, pc);
                                                                            pc += 2;
                                                                            instOffset += 2;
                                                                            break decode;
                                                                        }
                                                                        case 52701: {
                                                                            switch (inst & 0xdfdf) {
                                                                                case 52701: {
                                                                                    if ((inst & 0x2000) == 0x0) {
                                                                                        call_nn(((inst >>> 16) & 0xffff), pc, 4);
                                                                                        pc = this.pc & 0xFFFF;
                                                                                        instOffset = (int) pc;
                                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                            return;
                                                                                        }
                                                                                        break decode;
                                                                                    }
                                                                                    if ((inst & 0x2000) == 0x2000) {
                                                                                        xy_ed(pc);
                                                                                        pc = this.pc & 0xFFFF;
                                                                                        instOffset = (int) pc;
                                                                                        if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                            return;
                                                                                        }
                                                                                        break decode;
                                                                                    }
                                                                                    throw illegalInstruction();
                                                                                }
                                                                                case 56797: {
                                                                                    xy_chain(pc);
                                                                                    pc = this.pc & 0xFFFF;
                                                                                    instOffset = (int) pc;
                                                                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                                        return;
                                                                                    }
                                                                                    break decode;
                                                                                }
                                                                                default:
                                                                                    throw illegalInstruction();
                                                                            }
                                                                        }
                                                                        default:
                                                                            throw illegalInstruction();
                                                                    }
                                                                }
                                                                case 6: {
                                                                    alu_a_n(((inst >>> 11) & 0x7), ((inst >>> 16) & 0xff));
                                                                    pc += 3;
                                                                    instOffset += 3;
                                                                    break decode;
                                                                }
                                                                case 7: {
                                                                    rst(((inst >>> 11) & 0x7), pc, 2);
                                                                    pc = this.pc & 0xFFFF;
                                                                    instOffset = (int) pc;
                                                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                                                        return;
                                                                    }
                                                                    break decode;
                                                                }
                                                                default:
                                                                    throw illegalInstruction();
                                                            }
                                                        }
                                                        default:
                                                            throw illegalInstruction();
                                                    }
                                                }
                                                default:
                                                    throw illegalInstruction();
                                            }
                                        }
                                        default:
                                            throw illegalInstruction();
                                    }
                                }
                                case 6: {
                                    alu_a_n(((inst >>> 3) & 0x7), ((inst >>> 8) & 0xff));
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 7: {
                                    rst(((inst >>> 3) & 0x7), pc, 1);
                                    pc = this.pc & 0xFFFF;
                                    instOffset = (int) pc;
                                    if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                                        return;
                                    }
                                    break decode;
                                }
                                default:
                                    throw illegalInstruction();
                            }
                        }
                        default:
                            throw illegalInstruction();
                    }
                }

                pc &= 0xFFFF;
                instOffset = (int) pc;
                if (singleStep || cycles >= cycleLimit || interruptsPending()) {
                    this.pc = pc;
                    return;
                }
            }
        } catch (final Z80IllegalInstructionException e) {
            this.pc = pc & 0xFFFF;
            throw new IllegalStateException("Z80 decoder is not total.", e);
        }
    }

    private void interpretTrace$instructionGroup0(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int nn = ((inst >>> 8) & 0xffff);
        if ((inst & 0x10) == 0x0) {
            ld_inn_hl(nn);
            return;
        }
        if ((inst & 0x10) == 0x10) {
            ld_inn_a(nn);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup1(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int nn = ((inst >>> 8) & 0xffff);
        if ((inst & 0x10) == 0x0) {
            ld_hl_inn(nn);
            return;
        }
        if ((inst & 0x10) == 0x10) {
            ld_a_inn(nn);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup2(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int rp = ((inst >>> 4) & 0x3);
        if ((inst & 0x8) == 0x0) {
            inc_rp(rp);
            return;
        }
        if ((inst & 0x8) == 0x8) {
            dec_rp(rp);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup3(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x38) == 0x30) {
            inc_hli();
            return;
        }
        inc_r(((inst >>> 3) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup4(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x38) == 0x30) {
            dec_hli();
            return;
        }
        dec_r(((inst >>> 3) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup5(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int n = ((inst >>> 8) & 0xff);
        if ((inst & 0x38) == 0x30) {
            ld_hli_n(n);
            return;
        }
        ld_r_n(((inst >>> 3) & 0x7), n);
        return;
    }

    private void interpretTrace$instructionGroup6(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x38) >>> 3)) {
            case 0: {
                rlca();
                return;
            }
            case 1: {
                rrca();
                return;
            }
            case 2: {
                rla();
                return;
            }
            case 3: {
                rra();
                return;
            }
            case 4: {
                daa();
                return;
            }
            case 5: {
                cpl();
                return;
            }
            case 6: {
                scf();
                return;
            }
            case 7: {
                ccf();
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace$instructionGroup7(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3f) == 0x36) {
            if (halt()) {
                return 1;
            }
            return 0;
        }
        if ((inst & 0x7) == 0x6) {
            ld_r_hli(((inst >>> 3) & 0x7));
            return 0;
        }
        if ((inst & 0x38) == 0x30) {
            ld_hli_r((inst & 0x7));
            return 0;
        }
        ld_r_r(((inst >>> 3) & 0x7), (inst & 0x7));
        return 0;
    }

    private void interpretTrace$instructionGroup8(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int alu = ((inst >>> 3) & 0x7);
        if ((inst & 0x7) == 0x6) {
            alu_a_hli(alu);
            return;
        }
        alu_a_r(alu, (inst & 0x7));
        return;
    }

    private int interpretTrace$instructionGroup9(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (inst & 0xcf) {
            case 193: {
                pop_rp2(((inst >>> 4) & 0x3));
                return 0;
            }
            case 201: {
                return interpretTrace$instructionGroup10(inst, pc);
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace$instructionGroup10(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x30) >>> 4)) {
            case 0: {
                ret();
                return 4;
            }
            case 1: {
                exx();
                return 0;
            }
            case 2: {
                jp_hl();
                return 4;
            }
            case 3: {
                ld_sp_hl();
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup11(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int b_op = ((inst >>> 11) & 0x7);
        switch (((inst & 0xc000) >>> 14)) {
            case 0: {
                interpretTrace$instructionGroup12(inst, pc, b_op);
                return;
            }
            case 1: {
                interpretTrace$instructionGroup13(inst, pc, b_op);
                return;
            }
            case 2: {
                interpretTrace$instructionGroup14(inst, pc, b_op);
                return;
            }
            case 3: {
                interpretTrace$instructionGroup15(inst, pc, b_op);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup12(final int inst, final long pc, final int arg0) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x700) == 0x600) {
            rot_hli(arg0);
            return;
        }
        rot_r(arg0, ((inst >>> 8) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup13(final int inst, final long pc, final int arg0) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x700) == 0x600) {
            bit_hli(arg0);
            return;
        }
        bit_r(arg0, ((inst >>> 8) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup14(final int inst, final long pc, final int arg0) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x700) == 0x600) {
            res_hli(arg0);
            return;
        }
        res_r(arg0, ((inst >>> 8) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup15(final int inst, final long pc, final int arg0) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x700) == 0x600) {
            set_hli(arg0);
            return;
        }
        set_r(arg0, ((inst >>> 8) & 0x7));
        return;
    }

    private void interpretTrace$instructionGroup16(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int rp = ((inst >>> 12) & 0x3);
        if ((inst & 0x800) == 0x0) {
            sbc_hl_rp(rp);
            return;
        }
        if ((inst & 0x800) == 0x800) {
            adc_hl_rp(rp);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup17(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int nn = ((inst >>> 16) & 0xffff);
        final int rp = ((inst >>> 12) & 0x3);
        if ((inst & 0x800) == 0x0) {
            ld_inn_rp(rp, nn);
            return;
        }
        if ((inst & 0x800) == 0x800) {
            ld_rp_inn(rp, nn);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup18(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x3000) >>> 12)) {
            case 0: {
                interpretTrace$instructionGroup19(inst, pc);
                return;
            }
            case 1: {
                interpretTrace$instructionGroup20(inst, pc);
                return;
            }
            case 2: {
                interpretTrace$instructionGroup21(inst, pc);
                return;
            }
            case 3: {
                ed_noni();
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup19(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x800) == 0x0) {
            ld_i_a();
            return;
        }
        if ((inst & 0x800) == 0x800) {
            ld_r_a();
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup20(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x800) == 0x0) {
            ld_a_i();
            return;
        }
        if ((inst & 0x800) == 0x800) {
            ld_a_r();
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup21(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x800) == 0x0) {
            rrd();
            return;
        }
        if ((inst & 0x800) == 0x800) {
            rld();
            return;
        }
        throw illegalInstruction();
    }

    private int interpretTrace$instructionGroup22(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (inst & 0xe0ff) {
            case 33005: {
                ed_noni();
                return 0;
            }
            case 41197: {
                return interpretTrace$instructionGroup23(inst, pc);
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace$instructionGroup23(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int dir = ((inst >>> 11) & 0x1);
        final int rep = ((inst >>> 12) & 0x1);
        switch (inst & 0xe4ff) {
            case 41197: {
                return interpretTrace$instructionGroup24(inst, pc, dir, rep);
            }
            case 42221: {
                ed_noni();
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace$instructionGroup24(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x300) >>> 8)) {
            case 0: {
                if (ldx(arg1, arg0, pc)) {
                    return 4;
                }
                return 0;
            }
            case 1: {
                if (cpx(arg1, arg0, pc)) {
                    return 4;
                }
                return 0;
            }
            case 2: {
                if (inx(arg1, arg0, pc)) {
                    return 4;
                }
                return 0;
            }
            case 3: {
                if (outx(arg1, arg0, pc)) {
                    return 4;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup25(final int inst, final long pc, final int arg0, final int arg1, final int arg2) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3000) == 0x2000) {
            ld_xy_nn(arg2, arg0);
            return;
        }
        ld_rp_nn(arg1, arg0);
        return;
    }

    private void interpretTrace$instructionGroup26(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int nn = ((inst >>> 16) & 0xffff);
        if ((inst & 0x1000) == 0x0) {
            ld_inn_xy(((inst >>> 5) & 0x1), nn);
            return;
        }
        if ((inst & 0x1000) == 0x1000) {
            ld_inn_a(nn);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup27(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int nn = ((inst >>> 16) & 0xffff);
        if ((inst & 0x1000) == 0x0) {
            ld_xy_inn(((inst >>> 5) & 0x1), nn);
            return;
        }
        if ((inst & 0x1000) == 0x1000) {
            ld_a_inn(nn);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup28(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x800) == 0x0) {
            interpretTrace$instructionGroup29(inst, pc);
            return;
        }
        if ((inst & 0x800) == 0x800) {
            interpretTrace$instructionGroup30(inst, pc);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace$instructionGroup29(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3000) == 0x2000) {
            inc_xy(((inst >>> 5) & 0x1));
            return;
        }
        inc_rp(((inst >>> 12) & 0x3));
        return;
    }

    private void interpretTrace$instructionGroup30(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3000) == 0x2000) {
            dec_xy(((inst >>> 5) & 0x1));
            return;
        }
        dec_rp(((inst >>> 12) & 0x3));
        return;
    }

    private void interpretTrace$instructionGroup31(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x3800) >>> 11)) {
            case 0: {
                rlca();
                return;
            }
            case 1: {
                rrca();
                return;
            }
            case 2: {
                rla();
                return;
            }
            case 3: {
                rra();
                return;
            }
            case 4: {
                daa();
                return;
            }
            case 5: {
                cpl();
                return;
            }
            case 6: {
                scf();
                return;
            }
            case 7: {
                ccf();
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace$instructionGroup32(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (inst & 0xcfdf) {
            case 49629: {
                interpretTrace$instructionGroup33(inst, pc);
                return 0;
            }
            case 51677: {
                return interpretTrace$instructionGroup34(inst, pc);
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup33(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3000) == 0x2000) {
            pop_xy(((inst >>> 5) & 0x1));
            return;
        }
        pop_rp2(((inst >>> 12) & 0x3));
        return;
    }

    private int interpretTrace$instructionGroup34(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        switch (((inst & 0x3000) >>> 12)) {
            case 0: {
                ret();
                return 4;
            }
            case 1: {
                exx();
                return 0;
            }
            case 2: {
                jp_xy(((inst >>> 5) & 0x1));
                return 4;
            }
            case 3: {
                ld_sp_xy(((inst >>> 5) & 0x1));
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup35(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        final int b_op = ((inst >>> 27) & 0x7);
        final int d = BitUtils.extendSign(((inst >>> 16) & 0xff), 8);
        final int r = ((inst >>> 24) & 0x7);
        final int xy = ((inst >>> 5) & 0x1);
        switch (((inst & 0xc0000000) >>> 30)) {
            case 0: {
                qrot(xy, b_op, r, d);
                return;
            }
            case 1: {
                qbit(xy, b_op, d);
                return;
            }
            case 2: {
                qres(xy, b_op, r, d);
                return;
            }
            case 3: {
                qset(xy, b_op, r, d);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace$instructionGroup36(final int inst, final long pc) throws li.cil.sedna.z80.exception.Z80IllegalInstructionException {
        if ((inst & 0x3000) == 0x2000) {
            push_xy(((inst >>> 5) & 0x1));
            return;
        }
        push_rp2(((inst >>> 12) & 0x3));
        return;
    }
}
