package com.lspatch.android.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;

    /** The chosen {@code android:versionCode}, or null when the app kept its own. */
    public final Integer versionCode;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    /**
     * Whether the loader dex was injected straight into the host package rather than kept as an
     * asset. Recorded so a re-patch driven from an installed app can reproduce the choice; older
     * patched apps have no such key, and Gson leaves the primitive {@code false} for them -- which
     * is exactly what those apps were built with.
     */
    public final boolean injectDex;

    /**
     * The {@code <uses-permission>} names the patch added on top of the app's own, or null for an
     * apk patched before this was recorded. Kept here, unlike the other manifest overrides, because
     * a re-patch recovers the *original* apks -- which never had these -- so without this record a
     * loader update would silently drop the permissions a module depends on.
     */
    public final String[] addedPermissions;
    public final LSPConfig lspConfig;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            Integer versionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex,
            String[] addedPermissions
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.versionCode = versionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.injectDex = injectDex;
        this.addedPermissions = addedPermissions;
        this.lspConfig = LSPConfig.instance;
    }
}
