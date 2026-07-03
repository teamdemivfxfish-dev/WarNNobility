package com.newtl.warnnobility.net;

import com.newtl.warnnobility.net.OpenChanceryMsg.Target;
import com.newtl.warnnobility.nobility.NobilityManager;
import com.newtl.warnnobility.nobility.NobleData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Assembles the Chancery console view for a player: standing, the advance option, and the peers
 *  they can seal. */
public final class ChanceryConsole {

    private ChanceryConsole() {}

    public static OpenChanceryMsg build(MinecraftServer server, ServerPlayer viewer) {
        NobilityManager m = NobilityManager.get(server);
        NobleData d = m.getOrCreate(viewer);

        String advanceLabel = m.advanceLabel(d);

        // peers of the same rank who are gathering seals: the viewer may back one of them
        List<Target> sealTargets = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.getUUID().equals(viewer.getUUID())) continue;
            NobleData od = m.getOrCreate(other);   // also refreshes their stored name
            if (od.rankIndex == d.rankIndex && m.seeksSupport(od.rankIndex)) {
                sealTargets.add(new Target(od.id, od.name));
            }
        }

        return new OpenChanceryMsg(
                m.rankName(d),
                m.standingLines(d),
                !advanceLabel.isEmpty(),
                advanceLabel,
                m.progressHint(d),
                sealTargets,
                d.female,
                m.holdsDomain(d.rankIndex),
                d.domainName,
                m.domainKind(d.rankIndex));
    }
}
