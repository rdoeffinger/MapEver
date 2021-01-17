/* Copyright (C) 2014,2015 Björn Stelter, Jan Müller
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
package de.hu_berlin.informatik.spws2014.mapever.entzerrung

import android.content.Context
import android.graphics.Point
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import de.hu_berlin.informatik.spws2014.mapever.R
import de.hu_berlin.informatik.spws2014.mapever.Settings.Companion.getPreference_livMultitouch
import de.hu_berlin.informatik.spws2014.mapever.largeimageview.OverlayIcon

/**
 * Erstelle Eckpunkt an bestimmter Position.
 *
 * @param parentEView die EntzerrungsView
 * @param positionArg Bildkoordinaten des Punktes
 */
class CornerIcon(parentEView: EntzerrungsView, positionArg: Point) : OverlayIcon(parentEView) {
    // Context der Activity
    private val context: Context = parentEView.context

    // Bildkoordinaten vor einem Drag-Vorgang (zwecks Drag-Abbruch)
    private var cornerPosition_preDrag: Point? = null

    // Offset des Angriffspunkt für Drag-Event (an welcher Stelle des Icons wird es gezogen?)
    private var dragOriginOffsetX = 0f
    private var dragOriginOffsetY = 0f

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

    // ////////////////////////////////////////////////////////////////////////
    // //////////// PROPERTIES
    // ////////////////////////////////////////////////////////////////////////
    // Bildkoordinaten des Eckpunktes
    var position: Point = positionArg
        set(position) {
            // Koordinaten auf Bildgröße beschränken
            position.x = position.x.coerceIn(0, parentLIV.imageWidth - 1)
            position.y = position.y.coerceIn(0, parentLIV.imageHeight - 1)
            field = position

            // Darstellung aktualisieren
            update()
        }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// EVENT HANDLERS
    // ////////////////////////////////////////////////////////////////////////
    // ////// DRAG AND DROP
    override fun onDragDown(pointerID: Int, screenX: Float, screenY: Float): Boolean {
        Log.d("CornerIcon/onDragDown", "[$position] start drag pointerID $pointerID, screen pos $screenX/$screenY")

        // Falls Multitouch nicht aktiviert ist und bereits ein Icon gedraggt wird, keinen weiteren Drag-Vorgang
        // starten und alle laufenden abbrechen.
        if (!getPreference_livMultitouch(context) && parentLIV.isCurrentlyDragging) {
            parentLIV.cancelAllDragging()
            return true
        }

        // Bildschirmkoordinaten in Bildkoordinaten umwandeln
        val imagePos = parentLIV.screenToImagePosition(screenX, screenY)

        // Merke den Angriffspunkt des Drags als Offset (wenn man den Eckpunkt nicht mittig sondern an der Seite
        // anfässt, dann zieht man den Eckpunkt auch an der Seite, statt dass er automatisch auf die Fingerposition
        // zentriert wird).
        dragOriginOffsetX = imagePos.x - imagePositionX
        dragOriginOffsetY = imagePos.y - imagePositionY

        // Alte Position des Eckpunkts merken, um sie im Falle eines Drag-Abbruchs zurückzusetzen
        cornerPosition_preDrag = position

        // Starte Drag-Vorgang (merke Pointer-ID, um onDragMove's zu erhalten)
        startDrag(pointerID)

        // Event wurde behandelt.
        return true
    }

    override fun onDragMove(screenX: Float, screenY: Float): Boolean {
        Log.d("CornerIcon/onDragMove", "[$position] dragging (pointer $dragPointerID) on screen pos $screenX/$screenY")

        // Bildschirmkoordinaten in Bildkoordinaten umwandeln
        val imagePos = parentLIV.screenToImagePosition(screenX, screenY)

        // Neue Position des Eckpunktes setzen
        position = Point(
                (imagePos.x - dragOriginOffsetX).toInt(),
                (imagePos.y - dragOriginOffsetY).toInt())

        // Eckpunkte sortieren lassen
        (parentLIV as EntzerrungsView).sortCorners()

        // Event wurde behandelt.
        return true
    }

    override fun onDragUp(screenX: Float, screenY: Float) {
        Log.d("CornerIcon/onDragUp", "[$position] stop dragging (pointer $dragPointerID) on screen pos $screenX/$screenY")

        // Stoppe Drag-Vorgang (um keine onDragMove's mehr zu erhalten)
        stopDrag()

        // Im Falle eines Drag-Abbruchs (screenX = screenY = Float.NaN), Iconposition zurücksetzen
        if (java.lang.Float.isNaN(screenX) || java.lang.Float.isNaN(screenY)) {
            position = cornerPosition_preDrag!!
            cornerPosition_preDrag = null
        }

        // Angriffspunkt des Drags zurücksetzen
        dragOriginOffsetY = 0f
        dragOriginOffsetX = dragOriginOffsetY
    }

    companion object {
        // Resource des zu verwendenden Bildes
        private const val cornerImageResource = R.drawable.entzerrung_corner
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS
    // ////////////////////////////////////////////////////////////////////////
    init {
        // Superkonstruktor, registriert Icon bei der LIV

        // Save activity context for later...

        // Appresource als Bild setzen
        drawable = ResourcesCompat.getDrawable(parentEView.resources, cornerImageResource, null)

        // Setze Position
        position = positionArg
    }
}
