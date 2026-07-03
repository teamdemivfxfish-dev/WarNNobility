package com.newtl.warnnobility.client;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only cache of which War 'n Taxes vassal colonies are still PROVISIONAL nobility seals for the
 * local player (synced by {@link com.newtl.warnnobility.net.VassalStatusMsg}). Read by the VassalsPage
 * mixin to tag those rows " (Provisional)" in the tax book. Concurrent because the netty thread writes it
 * and the render thread reads it.
 */
public final class ClientVassalStatus {

    private ClientVassalStatus() {}

    private static final Set<Integer> PROVISIONAL = ConcurrentHashMap.newKeySet();

    public static void set(Collection<Integer> colonyIds) {
        PROVISIONAL.clear();
        PROVISIONAL.addAll(colonyIds);
    }

    public static boolean isProvisional(int colonyId) {
        return PROVISIONAL.contains(colonyId);
    }
}
