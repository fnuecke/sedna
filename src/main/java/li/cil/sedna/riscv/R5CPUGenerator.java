package li.cil.sedna.riscv;

import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.decoder.DecoderGenerator;
import org.apache.logging.log4j.core.util.Throwables;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class R5CPUGenerator {
    @SuppressWarnings("unchecked")
    private static final Class<R5CPU> GENERATED_CLASS = (Class<R5CPU>) generateClass();
    private static final Constructor<R5CPU> GENERATED_CLASS_CTOR;

    public static Class<R5CPU> getGeneratedClass() {
        return GENERATED_CLASS;
    }

    public static R5CPU create(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        try {
            return GENERATED_CLASS_CTOR.newInstance(physicalMemory, rtc);
        } catch (final InvocationTargetException e) {
            Throwables.rethrow(e.getCause());
            throw new AssertionError();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static {
        try {
            GENERATED_CLASS_CTOR = GENERATED_CLASS.getConstructor(MemoryMap.class, RealTimeCounter.class);
            GENERATED_CLASS_CTOR.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> generateClass() {
        try {
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            final ClassRemapper remapper = new ClassRemapper(writer, new Remapper() {
                private final String TEMPLATE_NAME = Type.getInternalName(R5CPUTemplate.class);

                @Override
                public String map(final String internalName) {
                    if (internalName.equals(TEMPLATE_NAME)) {
                        return TEMPLATE_NAME + "$Generated";
                    } else {
                        return super.map(internalName);
                    }
                }
            });
            final DecoderGenerator generator = new DecoderGenerator(remapper,
                    R5Instructions.getDecoderTree(),
                    R5Instructions::getDefinition,
                    R5IllegalInstructionException.class,
                    "interpretTrace",
                    "decode");

            final ClassReader reader = new ClassReader(R5CPUTemplate.class.getName());
            reader.accept(generator, ClassReader.EXPAND_FRAMES);

            final byte[] bytes = writer.toByteArray();

            final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(R5CPUTemplate.class.getClassLoader(), null, bytes, 0, bytes.length);
        } catch (final Throwable e) {
            throw new AssertionError(e);
        }
    }
}
