// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.preferences;

/**
 * Visitor interface for {@link Setting} implementations.
 * @since 9759
 */
public interface SettingVisitor {
    /**
     * Visitor call for {@link StringSetting}.
     * @param value string setting
     */
    void visit(StringSetting value);

    /**
     * Visitor call for {@link ListSetting}.
     * @param value list setting
     */
    void visit(ListSetting value);

    /**
     * Visitor call for {@link ListListSetting}.
     * @param value list list setting
     */
    void visit(ListListSetting value);

    /**
     * Visitor call for {@link MapListSetting}.
     * @param value map list setting
     */
    void visit(MapListSetting value);
}
