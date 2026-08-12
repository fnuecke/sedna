package li.cil.sedna;

import li.cil.sedna.utils.SoftDouble;
import li.cil.sedna.utils.SoftFloat;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static li.cil.sedna.utils.SoftFloat.FLAG_INEXACT;
import static li.cil.sedna.utils.SoftFloat.RM_RNE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class SoftFloatExactnessTests {
    @Test
    public void exactDoubleResultsAreExact() {
        final SoftDouble fpu = new SoftDouble();
        final Random random = new Random(0);
        for (int i = 0; i < 100000; i++) {
            // 26-bit factors keep products exactly representable in a double.
            final double x = (random.nextInt(1 << 26) + 1) * Math.scalb(1.0, random.nextInt(41) - 20);
            final double y = (random.nextInt(1 << 26) + 1) * Math.scalb(1.0, random.nextInt(41) - 20);
            final double product = x * y;

            fpu.flags.value = 0;
            assertEquals(Double.doubleToLongBits(product),
                    fpu.mul(Double.doubleToLongBits(x), Double.doubleToLongBits(y), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);

            fpu.flags.value = 0;
            assertEquals(Double.doubleToLongBits(x),
                    fpu.div(Double.doubleToLongBits(product), Double.doubleToLongBits(y), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);

            final double square = x * x;
            fpu.flags.value = 0;
            assertEquals(Double.doubleToLongBits(x),
                    fpu.sqrt(Double.doubleToLongBits(square), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);
        }
    }

    @Test
    public void exactFloatResultsAreExact() {
        final SoftFloat fpu = new SoftFloat();
        final Random random = new Random(0);
        for (int i = 0; i < 100000; i++) {
            // 12-bit factors keep products exactly representable in a float.
            final float x = (random.nextInt(1 << 12) + 1) * (float) Math.scalb(1.0, random.nextInt(41) - 20);
            final float y = (random.nextInt(1 << 12) + 1) * (float) Math.scalb(1.0, random.nextInt(41) - 20);
            final float product = x * y;

            fpu.flags.value = 0;
            assertEquals(Float.floatToIntBits(product),
                    fpu.mul(Float.floatToIntBits(x), Float.floatToIntBits(y), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);

            fpu.flags.value = 0;
            assertEquals(Float.floatToIntBits(x),
                    fpu.div(Float.floatToIntBits(product), Float.floatToIntBits(y), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);

            final float square = x * x;
            fpu.flags.value = 0;
            assertEquals(Float.floatToIntBits(x),
                    fpu.sqrt(Float.floatToIntBits(square), RM_RNE));
            assertEquals(0, fpu.flags.value & FLAG_INEXACT);
        }
    }

    @Test
    public void inexactResultsRaiseInexact() {
        final SoftDouble fpu64 = new SoftDouble();
        fpu64.flags.value = 0;
        assertEquals(Double.doubleToLongBits(1.0 / 3.0),
                fpu64.div(Double.doubleToLongBits(1.0), Double.doubleToLongBits(3.0), RM_RNE));
        assertNotEquals(0, fpu64.flags.value & FLAG_INEXACT);

        fpu64.flags.value = 0;
        assertEquals(Double.doubleToLongBits(Math.sqrt(2.0)),
                fpu64.sqrt(Double.doubleToLongBits(2.0), RM_RNE));
        assertNotEquals(0, fpu64.flags.value & FLAG_INEXACT);

        final SoftFloat fpu32 = new SoftFloat();
        fpu32.flags.value = 0;
        assertEquals(Float.floatToIntBits(1.0f / 3.0f),
                fpu32.div(Float.floatToIntBits(1.0f), Float.floatToIntBits(3.0f), RM_RNE));
        assertNotEquals(0, fpu32.flags.value & FLAG_INEXACT);

        fpu32.flags.value = 0;
        assertEquals(Float.floatToIntBits((float) Math.sqrt(2.0)),
                fpu32.sqrt(Float.floatToIntBits(2.0f), RM_RNE));
        assertNotEquals(0, fpu32.flags.value & FLAG_INEXACT);
    }
}
