package com.cobbleverse.cobblesyncbridge.util;

import java.util.UUID;

public final class SkinResolver {
    private SkinResolver() {}

    public static String skinRenderUrl(UUID uuid) {
        return "https://crafatar.com/renders/body/" + uuid.toString().replace("-", "") + "?overlay";
    }
}
