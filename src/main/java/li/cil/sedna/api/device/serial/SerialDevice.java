package li.cil.sedna.api.device.serial;

public interface SerialDevice {
    int read();

    boolean canPutByte();

    void putByte(byte value);

    default void flush() {
    }
}
