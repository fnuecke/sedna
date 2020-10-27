package li.cil.sedna.api.devicetree;

import li.cil.sedna.api.device.Device;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RegisterDeviceTreeProvider {
    Class<? extends Device> value();
}
