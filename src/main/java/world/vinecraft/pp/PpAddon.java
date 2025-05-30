package world.vinecraft.pp;

import java.io.IOException;

import org.bukkit.event.Listener;

import world.bentobox.bentobox.api.addons.Addon;
import world.vinecraft.pp.commands.PrayCommand;

public class PpAddon extends Addon implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Register command
        try {
            new PrayCommand(this, "pray", "amen");
        } catch (IOException e) {
            logError(e.getMessage());
            setState(State.DISABLED);
        }
    }

    @Override
    public void onDisable() {
    }

}
