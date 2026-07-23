package com.newtl.warnnobility;

import com.mojang.logging.LogUtils;
import com.newtl.warnnobility.domain.net.DomainNetwork;
import com.newtl.warnnobility.net.Network;
import org.slf4j.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Entrypoint. Registers config, the network payloads, and the Chancery Table block/item.
 * The nobility logic lives in {@code nobility/}, commands in {@code command/}.
 *
 * NeoForge injects the mod event bus and the ModContainer into the constructor (no
 * FMLJavaModLoadingContext), and registration uses DeferredRegister.Blocks/Items.
 */
@Mod(WarNNobility.MODID)
public class WarNNobility {

    public static final String MODID = "warnnobility";

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<ChanceryTableBlock> CHANCERY_TABLE = BLOCKS.registerBlock(
            "chancery_table",
            ChanceryTableBlock::new,
            BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD));

    public static final DeferredItem<BlockItem> CHANCERY_TABLE_ITEM =
            ITEMS.registerSimpleBlockItem("chancery_table", CHANCERY_TABLE);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    /** Render-only BE so a BlockEntityRenderer can draw a real open book on the Chancery lectern. */
    public static final Supplier<BlockEntityType<ChanceryTableBlockEntity>> CHANCERY_TABLE_BE =
            BLOCK_ENTITIES.register("chancery_table",
                    () -> BlockEntityType.Builder.of(ChanceryTableBlockEntity::new, CHANCERY_TABLE.get()).build(null));

    // ---- War Table (the collaborative war map; see com.newtl.warnnobility.warmap) -----------------
    // A War Frame mounts like an item frame; a 3x3 of them holds an atlas and becomes a live,
    // drawable briefing map. The anchor frame's BE owns the shared view, the marker lock, and the
    // persistent plan.

    public static final DeferredBlock<com.newtl.warnnobility.warmap.WarFrameBlock> WAR_FRAME =
            BLOCKS.registerBlock("war_frame",
                    com.newtl.warnnobility.warmap.WarFrameBlock::new,
                    BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.WOOD).noOcclusion());

    public static final DeferredItem<BlockItem> WAR_FRAME_ITEM =
            ITEMS.registerSimpleBlockItem("war_frame", WAR_FRAME);

    public static final Supplier<BlockEntityType<com.newtl.warnnobility.warmap.WarFrameBlockEntity>> WAR_FRAME_BE =
            BLOCK_ENTITIES.register("war_frame",
                    () -> BlockEntityType.Builder.of(
                            com.newtl.warnnobility.warmap.WarFrameBlockEntity::new, WAR_FRAME.get()).build(null));

    // ---- King-gate regalia (see RequirementType.CROWN) -------------------------------------------
    // The Shard has NO recipe: it is meant to be admin-granted or dropped by the POIs & Raid Bosses
    // mod, so reaching King always needs an out-of-band event. The Crown is crafted from it and is
    // consumed on coronation. Both reuse vanilla assets for now (ancient debris / golden helmet).

    /** "Shard from the Crown of Unification" - the unobtainable-by-crafting gate for the Crown. */
    public static final DeferredItem<Item> CROWN_SHARD =
            ITEMS.registerSimpleItem("crown_shard", new Item.Properties().stacksTo(16));

    /** The Crown, crafted from a Shard, consumed when a Duke declares themselves King. */
    public static final DeferredItem<Item> CROWN =
            ITEMS.registerSimpleItem("crown", new Item.Properties().stacksTo(1));

    public WarNNobility(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(Network::register);
        modBus.addListener(DomainNetwork::register);   // optional nobility-domain map (folded-in Domain Atlas)
        modBus.addListener(com.newtl.warnnobility.warmap.net.WarMapNetwork::register);  // the War Table
        modBus.addListener(this::addToCreativeTab);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(CHANCERY_TABLE_ITEM.get());
            event.accept(WAR_FRAME_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(CROWN_SHARD.get());
            event.accept(CROWN.get());
        }
    }
}
