package com.example.denicker;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.List;

@Mod(modid = Denicker.MODID, name = "Denicker", version = Denicker.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class Denicker {
    public static final String MODID = "denicker";
    public static final String VERSION = "2.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new DenickCommand());
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        Engine.fullReset();
        try {
            e.manager.channel().pipeline().addBefore("packet_handler", "denicker", new PacketSniffer());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private int tickCounter = 0;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++tickCounter % 10 != 0) return; // every 0.5s like the proxy's spectator loop
        try { Engine.tickSpectators(); } catch (Throwable t) { t.printStackTrace(); }
    }

    public static class DenickCommand extends CommandBase {
        @Override public String getCommandName() { return "denick"; }

        @Override public String getCommandUsage(ICommandSender s) {
            return "/denick [list|<name>|auto|tab|clean|target <name>|debug|clear|dump]";
        }

        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                Engine.msg("Works automatically like the proxy: FAKE nicks, real names, vanish and spectator alerts.");
                Engine.msg("\u00a7e/denick list\u00a7f  results so far     \u00a7e/denick <name>\u00a7f  check one player");
                Engine.msg("\u00a7e/denick auto\u00a7f  chat alerts on/off   \u00a7e/denick tab\u00a7f  tab rewrite on/off");
                Engine.msg("\u00a7e/denick clean\u00a7f  [CLEAN] lines on/off   \u00a7e/denick target <name|off>\u00a7f  reveal by UUID");
                Engine.msg("\u00a7e/denick debug\u00a7f  show decisions      \u00a7e/denick clear\u00a7f  reset");
                return;
            }
            String sub = args[0].toLowerCase();

            if (sub.equals("list")) {
                List<String> lines = Engine.listLines();
                if (lines.isEmpty()) Engine.msg("Nothing found yet.");
                else for (String l : lines) Engine.msg(l);

            } else if (sub.equals("auto")) {
                Engine.announce = !Engine.announce;
                Engine.msg("Chat alerts: " + (Engine.announce ? "\u00a7aON" : "\u00a7cOFF"));

            } else if (sub.equals("tab")) {
                Engine.tabRewrite = !Engine.tabRewrite;
                Engine.msg("Tab list rewrite: " + (Engine.tabRewrite ? "\u00a7aON" : "\u00a7cOFF"));
                Engine.refreshAllTab();

            } else if (sub.equals("clean")) {
                Engine.cleanLines = !Engine.cleanLines;
                Engine.msg("[CLEAN] lines: " + (Engine.cleanLines ? "\u00a7aON" : "\u00a7cOFF"));

            } else if (sub.equals("target")) {
                Engine.setTarget(args.length > 1 ? args[1] : "off");

            } else if (sub.equals("debug")) {
                Engine.debug = !Engine.debug;
                Engine.msg("Debug: " + (Engine.debug ? "\u00a7aON" : "\u00a7cOFF"));

            } else if (sub.equals("clear")) {
                Engine.fullReset();
                Engine.msg("Cleared.");

            } else if (sub.equals("dump")) {
                Engine.dump();
                Engine.msg("Dump written to logs/latest.log");

            } else if (sub.equals("check") && args.length > 1) {
                Engine.inspect(args[1]);

            } else {
                Engine.inspect(args[0]);
            }
        }
    }
}
