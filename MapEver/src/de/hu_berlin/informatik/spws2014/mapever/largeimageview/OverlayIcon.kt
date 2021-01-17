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
package de.hu_berlin.informatik.spws2014.mapever.largeimageview

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable

abstract class OverlayIcon protected constructor(
        // LargeImageView, an die das Overlay gebunden ist
        protected val parentLIV: LargeImageView) {
    // das Bild des Icons als Drawable
    protected var drawable: Drawable? = null
        set(_drawable) {
            field = _drawable

            // Boundaries/Bildgröße setzen
            drawable!!.setBounds(0, 0, drawable!!.minimumWidth, drawable!!.minimumHeight)
        }

    /**
     * True, wenn Icon gerade angezeigt wird (siehe [.hide] und [.show]).
     */
    // ist das Icon gerade sichtbar?
    var isVisible = true
        private set

    // Transparenz: Alpha-Wert
    private var overlayAlpha = 255

    /**
     * Wenn das Icon gerade gedraggt wird, dann gibt diese Funktion die PointerID des draggenden Fingers zurück.
     * Ansonsten -1, wenn das Icon nicht gedraggt wird.
     */
    // Drag and Drop: ID des Pointers (Fingers), mit dem das Icon gedraggt wird. -1 wenn nicht gedraggt.
    var dragPointerID = -1
        private set
    // ////// LAYOUT STUFF
    /**
     * Deregistriert das Icon von der LargeImageView (löscht es aus der OverlayIconList). Sollte nach dem Löschen eines
     * Icons aufgerufen werden.
     */
    fun detach() {
        parentLIV.detachOverlayIcon(this)
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// PROPERTIES
    // ////////////////////////////////////////////////////////////////////////
    // ////// DIMENSIONEN
    /**
     * Gibt die Breite des Icons zurück.
     */
    protected val width: Int
        get() = drawable?.intrinsicWidth ?: 0

    /**
     * Gibt die Höhe des Icons zurück.
     */
    protected val height: Int
        get() = drawable?.intrinsicHeight ?: 0
    // ////// POSITION AND OFFSET
    /**
     * X-Koordinate der Bildposition. Muss von Subklassen überschrieben werden, um die Position zu setzen.
     *
     * @return 0, wenn nicht überschrieben.
     */
    open val imagePositionX: Int
        get() = 0

    /**
     * Y-Koordinate der Bildposition. Muss von Subklassen überschrieben werden, um die Position zu setzen.
     *
     * @return 0, wenn nicht überschrieben.
     */
    open val imagePositionY: Int
        get() = 0

    /**
     * Bildoffset in X-Richtung. Muss von Subklassen überschrieben werden, wenn ein Offset erwünscht ist.
     *
     * @return 0, wenn nicht überschrieben.
     */
    open val imageOffsetX: Int
        get() = 0

    /**
     * Bildoffset in Y-Richtung. Muss von Subklassen überschrieben werden, wenn ein Offset erwünscht ist.
     *
     * @return 0, wenn nicht überschrieben.
     */
    open val imageOffsetY: Int
        get() = 0

    /**
     * Gibt ein Rechteck zurück, das relativ zur Bildposition angibt, welcher Bereich des Icons anklickbar ist.
     * Default-Implementation gibt die Dimensionen des Bildes verschoben um den ImageOffset zurück.
     * Kann überschrieben werden und darf null zurückgeben. null wird als "Icon ist nicht klickbar" interpretiert.
     */
    open val touchHitbox: Rect?
        get() = Rect(
                imageOffsetX,
                imageOffsetY,
                width + imageOffsetX,
                height + imageOffsetY)

    /**
     * Gibt mittels LargeImageView.imageToScreenPosition() die momentane Bildschirmposition (statt Bildposition) des
     * Icons zurück.
     */
    val screenPosition: PointF
        get() = parentLIV.imageToScreenPosition(imagePositionX.toFloat(), imagePositionY.toFloat())
    // ////// ICON DRAWABLE
    // ////// APPEARANCE
    /**
     * Setzt Sichtbarkeit des Icons.
     */
    fun setVisibility(vis: Boolean) {
        isVisible = vis
        update()
    }

    /**
     * Versteckt das Icon. Kann mit [.show] wieder angezeigt werden.
     */
    fun hide() {
        setVisibility(false)
    }

    /**
     * Zeigt ein vorher mit [.hide] verstecktes Icon wieder an.
     */
    fun show() {
        setVisibility(true)
    }

    /**
     * Setze Transparenz des Icons.
     *
     * @param newAlpha Wert von 0 (vollkommen transparent) bis 255 (undurchsichtig).
     */
    private fun setOverlayAlpha(newAlpha: Int) {
        overlayAlpha = newAlpha

        // Darstellung aktualisieren
        update()
    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// EVENT HANDLERS
    // ////////////////////////////////////////////////////////////////////////
    // ////// CLICKS
    /**
     * Führt einen Klick auf das Icon aus. Macht normalerweise nichts, kann überschrieben werden. Wurde das Event
     * behandelt muss false zurückgegeben werden, damit keine andere View das Event erhält.
     *
     * @param screenX X-Koordinate des Klicks relativ zum Bildschirm
     * @param screenY Y-Koordinate des Klicks relativ zum Bildschirm
     * @return Immer false, falls nicht überschrieben.
     */
    open fun onClick(screenX: Float, screenY: Float): Boolean {
        // return false: Event wurde nicht behandelt.
        return false
    }
    // ////// DRAG AND DROP
    /**
     * Markiere Icon als "wird jetzt mit Pointer pointerID gedraggt". Muss von onDragDown() aufgerufen werden, damit
     * onDragMove() getriggert wird. getDragPointerID() gibt dann (bis stopDrag()) pointerID zurück.
     *
     * @param pointerID Pointer-ID von 0 bis n
     */
    protected fun startDrag(pointerID: Int) {
        dragPointerID = pointerID
    }

    /**
     * Beende Drag-Aktion. Muss von onDragUp() aufgerufen werden, damit onDragMove() nicht mehr getriggert wird.
     */
    protected fun stopDrag() {
        dragPointerID = -1
    }

    /**
     * Wird von LargeImageView.onTouchEvent_dragAndDrop() getriggert, wenn ein Finger das Icon berührt.
     * Soll kein Drag and Drop implementiert werden, reicht die Standard-Implementierung, die false zurückgibt.
     * Damit nun auch onDragMove() getriggert wird, muss startDrag(pointerID) aufgerufen werden.
     * Wurde das Event behandelt, sollte true zurückgegeben werden, damit das Event nicht mehr von anderen Objekten
     * behandelt wird (und z.B. das Panning auslöst).
     *
     * @param pointerID ID des Pointers (Fingers), der das Icon berührt
     * @param screenX X-Koordinate des Klicks relativ zum Bildschirm
     * @param screenY Y-Koordinate des Klicks relativ zum Bildschirm
     * @return Immer false, falls nicht überschrieben.
     */
    open fun onDragDown(pointerID: Int, screenX: Float, screenY: Float): Boolean {
        // return false: Event wurde nicht behandelt.
        return false
    }

    /**
     * Wird von LargeImageView.onTouchEvent_dragAndDrop() getriggert, wenn getDragPointerID() einen Pointer > -1
     * zurückgibt und eben dieser Pointer für ein ACTION_MOVE gesorgt hat.
     * Wurde das Event behandelt, sollte true zurückgegeben werden, damit das Event nicht mehr von anderen Objekten
     * behandelt wird (und z.B. das Panning auslöst).
     *
     * @param screenX X-Koordinate des Klicks relativ zum Bildschirm
     * @param screenY Y-Koordinate des Klicks relativ zum Bildschirm
     * @return Immer false, falls nicht überschrieben.
     */
    open fun onDragMove(screenX: Float, screenY: Float): Boolean {
        // return false: Event wurde nicht behandelt.
        return false
    }

    /**
     * Wird von LargeImageView.onTouchEvent_dragAndDrop() getriggert, wenn getDragPointerID() einen Pointer > -1
     * zurückgibt und eben dieser Pointer für ein ACTION_(POINTER_)UP gesorgt hat.
     * Muss stopDrag() aufrufen, damit keine weiteren onDragMove()s mehr getriggert werden!
     * Werden Float.NaN als Parameter übergeben, gilt der Dragvorgang als "abgebrochen" (Handler kann sich z.B. um
     * das Zurücksetzen einer Drag-Verschiebung kümmern.)
     *
     * @param screenX X-Koordinate des Klicks relativ zum Bildschirm
     * @param screenY Y-Koordinate des Klicks relativ zum Bildschirm
     * @return Immer false, falls nicht überschrieben.
     */
    open fun onDragUp(screenX: Float, screenY: Float) {
        // (Ein Rückgabewert macht hier wenig Sinn. Wenn das Icon gedraggt wurde und der Dragvorgang beendet werden
        // soll, MUSS dieses Event behandelt werden. Andernfalls wird es gar nicht getriggert.)
        stopDrag()
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// DARSTELLUNG
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Zeichnet das Drawable (mit Alpha-Wert, und nur falls nicht hidden) auf einem (vorher translatierten) Canvas.
     */
    fun draw(canvas: Canvas) {
        // Canvas wurde schon entsprechend verschoben, sodass (0,0) nun der Iconposition (inkl. Offset) entspricht
        if (drawable != null && isVisible) {
            // Transparenz anwenden und Drawable zeichnen
            drawable!!.alpha = overlayAlpha
            drawable!!.draw(canvas)
        }
    }

    /**
     * Aktualisiert die Darstellung des Icons (ruft invalidate() auf).
     * Sollte immer aufgerufen werden, wenn z.B. die Position oder die Transparenz verändert wurde.
     */
    protected fun update() {
        parentLIV.invalidate()
    }
    // ////// ANIMATIONS
    /**
     * Starte Fade-Animation.
     *
     * @param from Start-Alphawert
     * @param to End-Alphawert
     * @param duration Dauer der Animation
     */
    @Suppress("UNUSED_PARAMETER")
    protected fun startFading(from: Float, to: Float, duration: Long) {
        // Erstelle Animation... selbsterklärende Parameter
        // Animation fadeAnim = new AlphaAnimation(from, to);
        // fadeAnim.setInterpolator(new AccelerateInterpolator());
        // fadeAnim.setDuration(duration);
        // fadeAnim.setFillEnabled(true);
        // fadeAnim.setFillAfter(true);
        //
        // this.startAnimation(fadeAnim);

        // TODO Animationen reimplementieren!
        setOverlayAlpha((to * 255).toInt())
    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND LAYOUT STUFF
    // ////////////////////////////////////////////////////////////////////////
    init {
        // Referenz auf die LargeImageView merken, zu der das Icon gehört

        // Icon bei der LIV registrieren
        parentLIV.attachOverlayIcon(this)
    }
}