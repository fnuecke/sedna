package li.cil.sedna.riscv;

import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.decoder.DecoderGenerator;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import org.apache.logging.log4j.core.util.Throwables;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class R5CPUGenerator {
    private static final String TEMPLATE_NAME = Type.getInternalName(R5CPUTemplate.class);
    private static final String GENERATED_NAME = TEMPLATE_NAME + "$Generated";

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
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(final String type1, final String type2) {
                    final String commonSuperClass;
                    if (type1.equals(GENERATED_NAME)) {
                        commonSuperClass = super.getCommonSuperClass(Type.getInternalName(R5CPUTemplate.class), type2);
                    } else if (type2.equals(GENERATED_NAME)) {
                        commonSuperClass = super.getCommonSuperClass(type1, Type.getInternalName(R5CPUTemplate.class));
                    } else {
                        return super.getCommonSuperClass(type1, type2);
                    }

                    if (commonSuperClass.equals(TEMPLATE_NAME)) {
                        return GENERATED_NAME;
                    }
                    return commonSuperClass;
                }

                @Override
                protected ClassLoader getClassLoader() {
                    return R5CPUGenerator.class.getClassLoader();
                }
            };
            final ClassRemapper remapper = new ClassRemapper(writer, new Remapper() {
                @Override
                public String map(final String internalName) {
                    if (internalName.equals(TEMPLATE_NAME)) {
                        return GENERATED_NAME;
                    } else {
                        return super.map(internalName);
                    }
                }
            });
            final DecoderGenerator generator64 = new DecoderGenerator(remapper,
                    R5Instructions.RV64.getDecoderTree(),
                    R5Instructions.RV64::getDefinition,
                    R5IllegalInstructionException.class,
                    "interpretTrace64",
                    "decode");
            final DecoderGenerator generator32 = new DecoderGenerator(generator64,
                    R5Instructions.RV32.getDecoderTree(),
                    R5Instructions.RV32::getDefinition,
                    R5IllegalInstructionException.class,
                    "interpretTrace32",
                    "decode");

            try (final InputStream stream = R5CPUTemplate.class.getClassLoader().getResourceAsStream(R5CPUTemplate.class.getName().replace('.', '/') + ".class")) {
                if (stream == null) {
                    throw new IOException("Could not load class file for class [" + R5CPUTemplate.class + "].");
                }

                final ClassReader reader = new ClassReader(stream);
                reader.accept(generator32, ClassReader.EXPAND_FRAMES);

                final byte[] bytes = writer.toByteArray();

                final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                defineClass.setAccessible(true);
                return (Class<?>) defineClass.invoke(R5CPUTemplate.class.getClassLoader(), null, bytes, 0, bytes.length);
            }
        } catch (final Throwable e) {
            throw new AssertionError(e);
        }
    }
}
