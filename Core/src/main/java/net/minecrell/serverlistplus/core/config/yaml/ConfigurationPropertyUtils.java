/*
 *        _____                     __    _     _   _____ _
 *       |   __|___ ___ _ _ ___ ___|  |  |_|___| |_|  _  | |_ _ ___
 *       |__   | -_|  _| | | -_|  _|  |__| |_ -|  _|   __| | | |_ -|
 *       |_____|___|_|  \_/|___|_| |_____|_|___|_| |__|  |_|___|___|
 *
 *  ServerListPlus - http://git.io/slp
 *    > The most customizable server status ping plugin for Minecraft!
 *  Copyright (c) 2014, Minecrell <https://github.com/Minecrell>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.core.config.yaml;

import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.config.UnknownConf;
import net.minecrell.serverlistplus.core.util.Helper;

import java.beans.IntrospectionException;

import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.MissingProperty;
import org.yaml.snakeyaml.introspector.Property;

import static net.minecrell.serverlistplus.core.logging.Logger.WARN;

public class ConfigurationPropertyUtils extends AbstractPropertyUtils {
    private final ServerListPlusCore core;

    public ConfigurationPropertyUtils(ServerListPlusCore core) {
        this.core = core;
        setSkipMissingProperties(true); // Will throw NoSuchMethodError on CraftBukkit
    }

    @Override // Print warning for unknown properties
    public Property getProperty(Class<?> type, String name, BeanAccess bAccess) throws IntrospectionException {
        if (bAccess != BeanAccess.FIELD) return super.getProperty(type, name, bAccess);
        Property p = super.getProperty(type, Helper.toLowerCase(name), bAccess);
        // Check if property was missing and notify user if necessary
        if ((p instanceof MissingProperty) && type != UnknownConf.class)
            core.getLogger().log(WARN, "Unknown configuration property: {} @ {}", name, type.getSimpleName());
        return p;
    }
}
