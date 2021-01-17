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

class ReferencePointIcon(parentMapView: MapView, position: Point2D, time: Long, isFadedOut: Boolean) : OverlayIcon(parentMapView) {
    /**
     * Gibt Bildkoordinaten des Referenzpunkts relativ zum Koordinatenursprung der Karte als Point2D zurück.
     */
    // Bildkoordinaten des Referenzpunktes
    var position: Point2D = position
        /**
         * Setze Bildkoordinaten des Referenzpunktes relativ zum Koordinatenursprung der Karte
         *
         * @param position neue Position
         */
        private set(position) {
            field = position
            // Darstellung aktualisieren
            update()
        }
    /**
     * Gibt den Zeitpunkt der Erzeugung (Akzeptanz) des Referenzpunkts zurück.
     */
    /**
     * Setzt den Zeitpunkt der Erzeugung des Referenzpunkts.
     */
    // Zeitpunkt, zu dem der Referenzpunkt erstellt wurde
    var timestamp: Long = 0

    // ////////////////////////////////////////////////////////////////////////
    // //////////// OVERLAYICON PROPERTY OVERRIDES
    // ////////////////////////////////////////////////////////////////////////
    override val imagePositionX: Int
        get() = position.x

    override val imagePositionY: Int
        get() = position.y

    override val imageOffsetX: Int
        get() = -width / 2

    override val imageOffsetY: Int
        get() = -height / 2

    override val touchHitbox: Rect
        // Die Hitbox ist doppelt so groß wie das Bild, damit man die kleinen Referenzpunkte besser anklicken kann.
        // (Ist sinnvoll.)
        get() = Rect(
                2 * imageOffsetX,
                2 * imageOffsetY,
                2 * (width + imageOffsetX),
                2 * (height + imageOffsetY))

    // ////////////////////////////////////////////////////////////////////////
    // //////////// EVENT HANDLERS
    // ////////////////////////////////////////////////////////////////////////
    override fun onClick(screenX: Float, screenY: Float): Boolean {
        val mapView = parentLIV as MapView

        // Registriere den zugehörigen Referenzpunkt als potentiellen Löschungskandidaten bei der MapView.
        // Falls dies im aktuellen Zustand nicht möglich ist, gibt die Funktion false zurück. Behandel das Event
        // dann als nicht behandelt, sodass es zur MapView weitergereicht wird.
        return mapView.registerAsDeletionCandidate(this)
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// DARSTELLUNG
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Starte Verblassungsanimation des Referenzpunktes.
     */
    fun fadeOut() {
        // von komplett sichtbar bis hiddenAlpha
        startFading(1f, hiddenAlpha, fadingTimeOut)
    }

    /**
     * Starte Animation, die den Referenzpunkt wieder sichtbar macht.
     */
    fun fadeIn() {
        // von hiddenAlpha bis komplett sichtbar
        startFading(hiddenAlpha, 1f, fadingTimeIn)
    }

    companion object {
        // Resource des zu verwendenden Bildes
        private const val refPointImageResource = R.drawable.ref_punkt

        // Dauer des Verblassens und letztendlich zu erreichender Alpha-Wert
        private const val hiddenAlpha = 0.4f
        private const val fadingTimeOut: Long = 2000
        private const val fadingTimeIn: Long = 200
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Erstelle Referenzpunkt an bestimmter Position. Verwende isFadedOut=false für neue Punkte, und true für das Laden
     * bestehender Punkte. Bitte darauf achten, time nur dann zu setzen, wenn der Punkt akzeptiert wurde (ggf. später
     * mit setTimestamp()).
     *
     * @param parentMapView die MapView
     * @param position Bildkoordinaten des Punktes
     * @param time Zeitpunkt der Erstellung des Punktes
     * @param isFadedOut Punkt ist bereits transparent
     */
    init {
        // Superkonstruktor, registriert Icon bei der LIV

        // Appresource als Bild setzen
        drawable = ResourcesCompat.getDrawable(parentMapView.resources, refPointImageResource, null)

        timestamp = time

        // Soll der Punkt zu Beginn voll sichtbar oder transparent sein?
        if (isFadedOut) {
            // Alpha-Wert setzen um Punkt transparent zu machen.
            // (NICHT setAlpha() verwenden, da die Animationen nicht mit dem dadurch gesetzten Alpha-Wert sondern
            // mit irgendeinem anderen internen Wert arbeiten. Beides wird dann akkumuliert, sodass fadeIn() den Wert
            // nicht auf 1 ändern lässt, sondern auf 1*currentAlpha... Total bekloppt.)
            startFading(hiddenAlpha, hiddenAlpha, 0)
        }
    }
}