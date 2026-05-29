package com.github.zly2006.xbackup.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import com.github.zly2006.xbackup.gui.ConfigGui

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent ->
            ConfigGui.createScreen(parent)
        }
    }
}
