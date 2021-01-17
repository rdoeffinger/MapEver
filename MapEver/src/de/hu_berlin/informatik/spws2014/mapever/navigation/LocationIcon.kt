/* Copyright (C) 2014,2015  Björn Stelter
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package de.hu_berlin.informatik.spws2014.mapever.navigation

import android.graphics.Rect
import androidx.core.content.res.ResourcesCompat
import de.hu_berlin.informatik.spws2014.ImagePositionLocator.Point2D
import de.hu_berlin.informatik.spws2014.mapever.R
import de.hu_berlin.informatik.spws2014.mapever.largeimageview.OverlayIcon

class LocationIcon(parentMapView: MapView) : OverlayIcon(parentMapView) {
    // ////////////////////////////////////////////////////////////////////////
    // //////////// OVERLAYICON PROPERTY OVERRIDES
    // ////////////////////////////////////////////////////////////////////////
    override val imagePositionX: Int
        get() = position.x

    override val imagePositionY: Int
        get() = position.y

    // ImageOffset: das Icon ist ein Punkt, die Position liegt also exakt in der Mitte des Icons
    override val imageOffsetX: Int
        get() = -width / 2

    override val imageOffsetY: Int
        get() = -height / 2

    override val touchHitbox: Rect?
        // Die LocationView als nicht klickbar markieren (nicht notwendig, aber slightly effizienter).
        get() = null
    // ////////////////////////////////////////////////////////////////////////
    // //////////// REFERENCEPOINT PROPERTIES
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Gibt Bildkoordinaten der Benutzerposition relativ zum Koordinatenursprung der Karte als Point2D zurück.
     */// Darstellung aktualisieren
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Bildkoordinaten der Benutzerposition relativ zum Koordinatenursprung der Karte.
     */
    var position = Point2D(0, 0)
        set(position) {
            field = position

            // Darstellung aktualisieren
            update()
        }

    // //////////// EVENT HANDLERS
    // ////////////////////////////////////////////////////////////////////////
    // kein onClick
    // TODO vielleicht könnte man hier aber trotzdem was interessantes machen (per Toast die GPS-Koordinaten
    // einblenden...?)
    // @Override
    // public boolean onClick(float screenX, float screenY) {
    // // return false: Event wurde nicht behandelt, wird an MapView weitergereicht
    // return false;
    // }
    companion object {
        // Resource des zu verwendenden Bildes
        private const val locationImageResource = R.drawable.current_position
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Erstellt ein Icon für die Anzeige der Benutzerposition.
     *
     * @param parentMapView die MapView
     */
    init {
        // Superkonstruktor, registriert Icon bei der LIV

        // Appresource als Bild setzen
        drawable = ResourcesCompat.getDrawable(parentMapView.resources, locationImageResource, null)
    }
}