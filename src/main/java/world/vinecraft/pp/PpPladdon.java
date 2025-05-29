package world.vinecraft.pp;

import world.bentobox.bentobox.api.addons.Addon;
import world.bentobox.bentobox.api.addons.Pladdon;



public class PpPladdon extends Pladdon
{
    Addon addon;
    @Override
    public Addon getAddon()
    {
        if (addon == null) {
            addon = new PpAddon();
        }
        return addon;
    }
}
