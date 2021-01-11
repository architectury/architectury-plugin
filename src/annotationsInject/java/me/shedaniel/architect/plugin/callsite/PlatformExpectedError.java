package me.shedaniel.architect.plugin.callsite;

public class PlatformExpectedError extends Error {
    public PlatformExpectedError() {
    }

    public PlatformExpectedError(String message) {
        super(message);
    }

    public PlatformExpectedError(String message, Throwable cause) {
        super(message, cause);
    }

    public PlatformExpectedError(Throwable cause) {
        super(cause);
    }

    public PlatformExpectedError(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
