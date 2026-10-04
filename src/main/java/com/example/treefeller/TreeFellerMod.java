package com.example.treefeller;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(TreeFellerMod.MODID)
public class TreeFellerMod {
    public static final String MODID = "treefeller";

    public TreeFellerMod() {
        MinecraftForge.EVENT_BUS.register(new TreeFellerHandler());
    }
}
