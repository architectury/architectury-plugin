package me.shedaniel.architect.plugin.callsite;

import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlatformMethods {
    public static CallSite platform(MethodHandles.Lookup lookup, String name, MethodType type) {
        Class<?> lookupClass = lookup.lookupClass();
        String lookupType = lookupClass.getName().replace("$", "") + "Impl";

        String platformExpectedClass = lookupType.substring(0, lookupType.lastIndexOf('.')) + "." + getModLoader() + "." +
                lookupType.substring(lookupType.lastIndexOf('.') + 1);
        Class<?> newClass;
        try {
            newClass = Class.forName(platformExpectedClass, false, lookupClass.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new PlatformExpectedError(lookupClass.getName() + "#" + name + " expected platform implementation in " + platformExpectedClass +
                    "#" + name + ", but the class doesn't exist!", exception);
        }
        MethodHandle platformMethod;
        try {
            platformMethod = lookup.findStatic(newClass, name, type);
        } catch (NoSuchMethodException exception) {
            throw new PlatformExpectedError(lookupClass.getName() + "#" + name + " expected platform implementation in " + platformExpectedClass +
                    "#" + name + ", but the method doesn't exist!", exception);
        } catch (IllegalAccessException exception) {
            throw new PlatformExpectedError(lookupClass.getName() + "#" + name + " expected platform implementation in " + platformExpectedClass +
                    "#" + name + ", but the method's modifier doesn't match the access requirements!", exception);
        }
        return new ConstantCallSite(platformMethod);
    }

    private static String modLoader = null;

    public static String getModLoader() {
        if (modLoader == null) {
            try {
                modLoader = (String) Class.forName("me.shedaniel.architectury.platform.Platform").getDeclaredMethod("getModLoader").invoke(null);
            } catch (Throwable ignored) {
                List<String> loader = new ArrayList<>();
                HashMap<String, String> MOD_LOADERS = new HashMap<>();
                MOD_LOADERS.put("net.fabricmc.loader.FabricLoader", "fabric");
                MOD_LOADERS.put("net.minecraftforge.fml.common.Mod", "forge");
                for (Map.Entry<String, String> entry : MOD_LOADERS.entrySet()) {
                    try {
                        PlatformMethods.class.getClassLoader().loadClass(entry.getKey());
                        loader.add(entry.getValue());
                        break;
                    } catch (ClassNotFoundException ignored1) {
                    }
                }
                if (loader.isEmpty())
                    throw new IllegalStateException("No detected mod loader!");
                if (loader.size() >= 2)
                    System.err.println("Detected multiple mod loaders! Something is wrong on the classpath! " + String.join(", ", loader));
                modLoader = loader.get(0);
            }
        }
        return modLoader;
    }
}
