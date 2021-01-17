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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import androidx.appcompat.widget.AppCompatImageView
import de.hu_berlin.informatik.spws2014.mapever.largeimageview.CachedImage.CacheMissResolvedCallback
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.*

open class LargeImageView : AppCompatImageView {
    // ////// BITMAP, TILE AND CACHE STUFF
    // Statische Bitmap, wird angezeigt, falls kein RegionDecoder initialisiert wurde
    private var staticBitmap: Bitmap? = null

    // Last-Recently-Used Cache für Tiles
    private var cachedImage: CachedImage? = null

    /**
     * Gibt die Breite des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    // Bildgröße, falls bekannt, sonst -1
    var imageWidth = -1
        private set

    /**
     * Gibt die Höhe des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    var imageHeight = -1
        private set

    // Paint-Objekt, das dazu da ist, das Hintergrundbild transparent zu machen (nur falls not null)
    private var bgAlphaPaint: Paint? = null

    // ////// DISPLAY, PAN- UND ZOOMWERTE
    // Aktuelle Pan-Center-Koordinaten und Zoom-Scale
    // NOTE: panPos wird jetzt andersherum betrachtet: positiver Wert = die Karte ist nach links/rechts verschoben
    // Die Pan-Center-Position gibt jetzt den Punkt an, der im Mittelpunkt des Sichtfeldes liegt.
    private var panCenterX = Float.NaN
    private var panCenterY = Float.NaN
    private var zoomScale = 1f
    private var rotation = 0
    private val sampledImageToScreenMatrix = Matrix()
    private val imageToScreenMatrix = Matrix()
    private val screenToImageMatrix = Matrix()
    private val panCenterMatrix = Matrix()

    // Sample-Stufe, wird automatisch aus zoomScale berechnet
    // (sampleSize: größer = geringere Auflösung; zoomScale: kleiner = weiter weg vom Bild)
    private var sampleSize = 1

    // Minimales und maximales Zoom-Level (Defaultwerte, werden pro Bild neu berechnet)
    private var minZoomScale = 0.1f
    private var maxZoomScale = 5.0f

    // ////// TOUCH EVENTS
    // Startkoordinaten bei einem Touch-Down-Event um Klicks zu erkennen
    private var touchCouldBeClick = false
    protected var touchStartX = 0f
    protected var touchStartY = 0f

    // SGD behandelt die Zoom-Gesten
    // Erstelle SGD, der fürs Zooming zuständig ist
    private val SGD = ScaleGestureDetector(context, ScaleListener())

    // Hilfsinformationen für Panning und Zooming
    private var panActive = false
    private var panActivePointerId = 0
    private var panLastTouchX = 0f
    private var panLastTouchY = 0f
    private var panLastTouchIsScaleFocus = false

    /**
     * Gibt true zurück, falls gerade ein OverlayIcon gedraggt wird.
     */
    // Findet gerade ein Drag-Vorgang statt?
    var isCurrentlyDragging = false
        private set

    // ////// OVERLAY ICONS
    private val overlayIconList = ArrayList<OverlayIcon>()

    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND INITIALIZATION
    // ////////////////////////////////////////////////////////////////////////
    protected constructor(context: Context) : super(context) {
    }

    protected constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
    }

    protected constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
    }

    override fun onSaveInstanceState(): Parcelable? {
        // Ich weiß nicht, was in dem Parcelable von View drinsteckt, aber ich wills auch nicht einfach wegwerfen...
        val parcel = super.onSaveInstanceState()
        val bundle = Bundle()
        bundle.putParcelable(SAVEDVIEWPARCEL, parcel)

        // Speichere Pan- und Zoom-Werte im State.
        bundle.putFloat(SAVEDPANX, panCenterX)
        bundle.putFloat(SAVEDPANY, panCenterY)
        bundle.putFloat(SAVEDZOOM, zoomScale)

        // TODO Können wir irgendwie das Bild/den Stream oder eine Referenz darauf speichern? Und viel
        // wichtiger, den Cache? Aktuell muss das Bild beim Drehen immer neu aufgebaut werden... -> #194
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val bundle = state as Bundle

        // Ich weiß nicht, was in dem Parcelable von View drinsteckt, aber ich wills auch nicht einfach wegwerfen...
        super.onRestoreInstanceState(bundle.getParcelable(SAVEDVIEWPARCEL))

        // // Pan und Zoom wiederherstellen
        val panX = bundle.getFloat(SAVEDPANX)
        val panY = bundle.getFloat(SAVEDPANY)
        val zoom = bundle.getFloat(SAVEDZOOM)
        setPanZoom(panX, panY, zoom) // calls update()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("LIV/onSizeChanged", "w, h, oldw, oldh: $w, $h, $oldw, $oldh")
        if (width == 0 || height == 0) {
            Log.e("LIV/onSizeChanged", "getWidth() or getHeight() is still zero! (w $width, h $height)")
            return
        }

        // If we have already loaded an image...
        if (cachedImage != null || staticBitmap != null) {
            onPostLoadImage(true)
        }
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// LADEN VON BILDERN
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Setzt einen InputStream als Bildquelle. Hierbei wird nach Möglichkeit über CachedImage ein BitmapRegionDecoder
     * instanziiert, der Bildteile nach Bedarf lädt, statt das gesamte Bild in eine Bitmap zu laden.
     *
     * Da der BitmapRegionDecoder nur JPEG und PNG unterstützt, wird bei anderen Formaten (z.B. GIF) sowie im
     * Fehlerfall eine IOException geworfen. (Es kann danach versucht werden, das Bild per setImageBitmap statisch
     * zu laden.)
     *
     * @param inputStream
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun setImageStreamOrFile(inputStream: InputStream?, file: String?) {
        // reset image references
        cachedImage = null
        staticBitmap = null

        // reset pan
        panCenterY = Float.NaN
        panCenterX = panCenterY

        // Instanziiere ein CachedImage über den gegebenen InputStream.
        // Wirft eine IOException, falls das Bild kein JPEG oder PNG ist, oder ein unerwarteter IO-Fehler auftrat.
        cachedImage = CachedImage(inputStream, file, object : CacheMissResolvedCallback {
            override fun onCacheMissResolved() {
                // Wenn nach einem Cache-Miss ein gesuchtes Tile generiert wurde, aktualisiere Ansicht
                update()
            }
        })

        // Breite und Höhe des Bildes zwischenspeichern
        imageWidth = cachedImage!!.width
        imageHeight = cachedImage!!.height

        // Berechnet unter anderem Zoom-Limits
        onPostLoadImage(false)
    }

    /**
     * Lädt statisch eine Bitmap als Bildquelle. Statisch bedeutet in diesem Fall, dass es nicht als large image
     * von CachedImage behandelt wird, sondern als ganzes Bitmap in die View geladen wird.
     */
    override fun setImageBitmap(bitmap: Bitmap?) {
        // Verwende statisch die Bitmap zum Darstellen
        cachedImage = null
        staticBitmap = bitmap

        // reset pan
        panCenterY = Float.NaN
        panCenterX = panCenterY
        if (staticBitmap != null) {
            imageWidth = staticBitmap!!.width
            imageHeight = staticBitmap!!.height

            // Berechnet unter anderem Zoom-Limits
            onPostLoadImage(false)
        }
    }

    override fun setImageResource(resId: Int) {
        assert(false)
    }

    /**
     * Lädt eine  raw Resource als Bildquelle. Hierbei wird nach Möglichkeit über CachedImage ein BitmapRegionDecoder
     * instanziiert, indem ein InputStream is erzeugt und setImageStream(is) aufgerufen wird.
     */
    protected fun setImageResourceRaw(resId: Int) {
        try {
            // Lade die Resource per Stream
            val stream = resources.openRawResource(+resId)
            setImageStreamOrFile(stream, null)
        } catch (e: IOException) {
            // Vermutlich schlägt dies fehl, weil die Resource weder JPEG noch PNG ist...
            Log.w("LIV/setImageStream", "Can't instantiate CachedImage:")
            Log.w("LIV/setImageStream", e.toString())

            // Fallback: Lade das Bild statisch als Bitmap (Stream muss neu geöffnet werden)
            val stream = resources.openRawResource(+resId)
            setImageBitmap(BitmapFactory.decodeStream(stream))
        }
    }

    /**
     * Lädt eine Bilddatei per Dateinamen als Bildquelle. Hierbei wird nach Möglichkeit über CachedImage ein
     * BitmapRegionDecoder instanziiert, indem ein InputStream is erzeugt und setImageStream(is) aufgerufen wird.
     */
    @Throws(FileNotFoundException::class)
    protected fun setImageFilename(filename: String?) {
        try {
            // Lade das Bild per Stream
            setImageStreamOrFile(null, filename)
        } catch (e: IOException) {
            // Vermutlich schlägt dies fehl, weil die Resource weder JPEG noch PNG ist...
            Log.w("LIV/setImageStream", "Can't instantiate CachedImage:")
            Log.w("LIV/setImageStream", e.toString())

            // Fallback: Lade das Bild statisch als Bitmap (Stream muss neu geöffnet werden)
            val stream: InputStream = FileInputStream(filename)
            setImageBitmap(BitmapFactory.decodeStream(stream))
        }
    }

    /**
     * Do not use! Won't be implemented.
     */
    @Deprecated("")
    override fun setImageURI(uri: Uri?) {
        // Not implemented because not needed... but we override it to avoid errors if someone does use it.

        // Reset stuff
        cachedImage = null
        staticBitmap = null
        panCenterY = Float.NaN
        panCenterX = panCenterY
        Log.e("LIV/setImageURI", "setImageURI not implemented!")
    }

    /**
     * Wird aufgerufen, nachdem ein Bild geladen wurde. Wenn überschrieben, dann unbedingt super.onPostLoadImage()
     * aufrufen, da diese Implementierung z.B. noch Zoom-Limits berechnet und das Bild zentriert.
     */
    protected open fun onPostLoadImage(calledByOnSizeChanged: Boolean) {
        // If called before onLayout() we don't know width and height yet... so we have to call this method later
        // in onSizeChanged again.
        if (width == 0 || height == 0) {
            Log.d("LIV/onPostLoadImage", "Couldn't execute onPostLoadImage yet, no width/height known!")
            return
        }
        // (re-)calculate MIN_ and MAX_ZOOM_SCALE
        calculateZoomScaleLimits()

        // If no pan has been set yet (just loaded): center image and zoom out until whole image is visible
        if (java.lang.Float.isNaN(panCenterX) || java.lang.Float.isNaN(panCenterY)) {
            setPanZoomFitImage()
        }
    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// IMAGE PROPERTIES
    // ////////////////////////////////////////////////////////////////////////
    private fun flipWH(): Boolean {
        return (rotation + 45) % 180 > 90
    }

    private val rotatedImageWidth: Int
        get() = if (flipWH()) imageHeight else imageWidth
    private val rotatedImageHeight: Int
        get() = if (flipWH()) imageWidth else imageHeight

    /**
     * Setze Transparenz des angezeigten Bildes ("Foreground" um Verwechslung mit setImageAlpha() zu vermeiden,
     * nicht Background, um Verwechslung mit setBackgroundColor() zu vermeiden... alles sehr verwirrend).
     *
     * @param newAlpha Wert von 0 (vollkommen transparent) bis 255 (undurchsichtig).
     */
    protected fun setForegroundAlpha(newAlpha: Int) {
        bgAlphaPaint = Paint()
        bgAlphaPaint!!.alpha = newAlpha
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// PANNING UND ZOOMING
    // ////////////////////////////////////////////////////////////////////////
    // ////// GETTERS AND SETTERS
    /** Setzt neue Pan-Center-Koordinaten (Bildpunkt, der im Sichtfeld zentriert wird).  */
    protected fun setPanCenter(newX: Float, newY: Float) {
        val newcenter = floatArrayOf(newX, newY)
        panCenterMatrix.mapPoints(newcenter)
        panCenterX = newcenter[0]
        panCenterY = newcenter[1]
        update()
    }

    //! Set rotation in degrees.
    //! Only 0, 90, 180 and 270 fully supported.
    fun setMapRotation(degrees: Int) {
        rotation = degrees
        setPanZoomFitImage()
        update()
    }

    /** Setzt neues Zoom-Level und berechnet Sample-Stufe neu.  */
    private fun setZoomScale(newZoomScale: Float) {
        zoomScale = newZoomScale

        // Zoom-Level darf Minimum und Maximum nicht unter-/überschreiten
        zoomScale = Math.max(minZoomScale, Math.min(zoomScale, maxZoomScale))

        // SampleSize neuberechnen
        sampleSize = calculateSampleSize(zoomScale)
        update()
    }

    /**
     * Setzt neue Pan-Center-Koordinaten (Bildpunkt, der im Sichtfeld zentriert wird) und neues Zoom-Level
     * (und berechnet Sample-Stufe neu).
     */
    private fun setPanZoom(newX: Float, newY: Float, newZoomScale: Float) {
        panCenterX = newX
        panCenterY = newY
        setZoomScale(newZoomScale) // calls update()
    }

    /**
     * Zentriert das Bild und setzt die Zoomstufe so, dass das ganze Bild sichtbar ist. Es wird also herausgezoomt,
     * bis kein Teil des Bildes mehr abgeschnitten wird, eventuell mit Letterbox/Pillarbox, aber es wird nicht
     * herangezoomt.
     */
    private fun setPanZoomFitImage() {
        if (width == 0 || height == 0 || imageWidth <= 0 || imageHeight <= 0) {
            Log.w("LIV/setPanZoomFitImage", "Some dimensions are still unknown: getWidth/getHeight: " + width +
                    "/" + height + ", imageWidth/imageHeight: " + imageWidth + "/" + imageHeight)
            return
        }

        // Bild zentrieren und so weit rauszoomen, dass das ganze Bild sichtbar ist (nicht jedoch das Bild abschneiden
        // oder heranzoomen)
        val centerX = (rotatedImageWidth / 2).toFloat()
        val centerY = (rotatedImageHeight / 2).toFloat()
        var fitZoomScale = Math.min(width.toFloat() / rotatedImageWidth, height.toFloat() / rotatedImageHeight)
        fitZoomScale = Math.min(1f, fitZoomScale)
        setPanZoom(centerX, centerY, fitZoomScale) // calls update()
    }

    /**
     * Berechnet optimale Zoom-Grenzen.
     */
    private fun calculateZoomScaleLimits() {
        if (width == 0 || height == 0 || imageWidth <= 0 || imageHeight <= 0) {
            Log.w("LIV/calcZoomScaleLimits", "Some dimensions are still unknown: getWidth/getHeight: " + width +
                    "/" + height + ", imageWidth/imageHeight: " + imageWidth + "/" + imageHeight)
            return
        }

        // Wie groß ist der Bildschirm relativ zur Karte?
        val relativeWidth = width.toDouble() / rotatedImageWidth
        val relativeHeight = height.toDouble() / rotatedImageHeight

        // Man kann nur soweit rauszoomen, dass die ganze Karte und noch etwas Rand auf den Bildschirm passt.
        minZoomScale = (0.8 * Math.min(1.0, Math.min(relativeHeight, relativeWidth))).toFloat()

        // Man kann bei hinreichend großen Bildern auf 6x ranzoomen, bei sehr kleinen Bildern maximal so, dass
        // sie den Bildschirm ausfüllen.
        maxZoomScale = Math.max(6.0, Math.max(relativeHeight, relativeWidth)).toFloat()
    }
    // ////// POSITIONSUMRECHNUNGEN
    /**
     * Gibt zu einer Bildschirmposition die (aktuelle) Bildposition zurück.
     */
    fun screenToImagePosition(screenX: Float, screenY: Float): PointF {
        val p = floatArrayOf(screenX, screenY)
        screenToImageMatrix.mapPoints(p)
        return PointF(p[0], p[1])
    }

    /**
     * Gibt zu einer Bildposition die (aktuelle) Bildschirmposition zurück.
     */
    fun imageToScreenPosition(imageX: Float, imageY: Float): PointF {
        val p = floatArrayOf(imageX, imageY)
        imageToScreenMatrix.mapPoints(p)
        return PointF(p[0], p[1])
    }
    // ////// EVENT HANDLERS
    /**
     * Wird getriggert, wenn sich Pan-Position oder Zoom durch ein Touch-Event ändern. Tut nichts, kann aber von
     * Subklassen überschrieben werden.
     */
    protected open fun onTouchPanZoomChange() {}

    /**
     * Wird getriggert, wenn ein Klick auf eine bestimmte Bildschirmposition stattfindet. Tut nichts, kann aber von
     * Subklassen überschrieben werden. Um Bildschirmkoordinaten in Bildkoordinaten umzuwandeln siehe
     * [.screenToImagePosition].
     *
     * @param clickX
     * @param clickY
     * @return true, falls das Event behandelt wurde.
     */
    protected open fun onClickPosition(clickX: Float, clickY: Float): Boolean {
        return false
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// OVERLAY ICONS
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Fügt ein OverlayIcon der Liste hinzu, sodass dieses in onDraw() gezeichnet wird. (Inklusive update())
     */
    fun attachOverlayIcon(icon: OverlayIcon) {
        overlayIconList.add(icon)
        update()
    }

    /**
     * Entfernt ein OverlayIcon aus der Liste, sodass dieses nicht mehr gezeichnet wird. (Inklusive update())
     */
    fun detachOverlayIcon(icon: OverlayIcon) {
        overlayIconList.remove(icon)
        update()
    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// TOUCH AND CLICK EVENT HANDLING
    // ////////////////////////////////////////////////////////////////////////
    // To allow zooming with keyboard (e.g. emulator)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_COMMA) {
            setZoomScale(zoomScale * 2)
            return true
        } else if (code == KeyEvent.KEYCODE_PERIOD) {
            setZoomScale(zoomScale / 2)
            return true
        }
        return super.onKeyDown(code, event)
    }

    // For zooming via mouse scrollwheel
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0 && event.action == MotionEvent.ACTION_SCROLL && event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f) {
            val factor = if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0) 0.90f else 1.10f


            // Calculate pan offsetting.
            val focusX = (event.x - width / 2) / zoomScale
            val focusY = (event.y - height / 2) / zoomScale
            val dx = focusX * (1 - factor)
            val dy = focusY * (1 - factor)
            val new_x = if (java.lang.Float.isNaN(panCenterX)) -dx else panCenterX - dx
            val new_y = if (java.lang.Float.isNaN(panCenterY)) -dy else panCenterY - dy
            setPanZoom(new_x, new_y, zoomScale * factor)
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    // ////// MAIN ONTOUCHEVENT
    /**
     * Behandelt Touchgesten, primär Klickerkennung, Panning und Zooming.
     * Kann überschrieben werden, um eigene Touchgesten zu implementieren. Soll Pan und Zoom vermieden werden, aber
     * Klicks dennoch erkannt werden, rufe super.onTouchEvent_clickDetection(event) auf, in welche die Klickerennung
     * ausgelagert wurde.
     *
     * @return true, falls Event behandelt wurde, sonst false. (Hier: eigentlich immer true.)
     */
    // Die Lint-Warnung "onTouchEvent should call performClick when a click is detected" wird fälschlicherweise(?)
    // angezeigt, obwohl onTouchEvent() onTouchEvent_clickDetection() aufruft, welche wiederum performClick() aufruft.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ////// KLICKERKENNUNG
        onTouchEvent_clickDetection(event)

        // ////// OVERLAYICON DRAG AND DROP

        // Wenn Drag/Drop erkannt wurde, wurde das Event behandelt.
        val dragAndDropHandled = onTouchEvent_dragAndDrop(event)

        // ////// PANNING UND ZOOMING

        // (auch bei true von onTouchEvent_dragAndDrop() aufrufen, weil manche panZoom-Events ausgeführt werden müssen)
        onTouchEvent_panZoom(event, dragAndDropHandled)
        return true
    }
    // ////// PANNING AND ZOOMING
    /**
     * Führt das Panning und Zooming durch. Wird als Teil von onTouchEvent aufgerufen.
     *
     * @return true. Auch Overrides sollten stets true zurückgeben (sonst kommen folgende Touch-Events nicht mehr an).
     */
    private fun onTouchEvent_panZoom(event: MotionEvent, dragAndDropHandled: Boolean) {
        // Was für ein MotionEvent wurde detektiert?
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {

                // Erste Berührung des Touchscreens (mit einem Finger)

                // Auch wenn wir gerade einen laufenden Drag-Vorgang haben, Pan-Start behandeln, da der
                // Drag-Vorgang abgebrochen werden könnte.

                // Position des ersten Pointers (Fingers) ermitteln
                val pointerIndex = event.actionIndex
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)

                // Position der Pan-Bewegung merken
                panLastTouchX = x
                panLastTouchY = y

                // Pointer-ID merken
                panActivePointerId = event.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {

                // Bewegung der Finger während Berührung

                // Nur behandeln, wenn wir einen aktiven Pan-Finger haben
                if (panActivePointerId == -1) return

                // Position des aktuellen Pointers ermitteln
                val pointerIndex = event.findPointerIndex(panActivePointerId)
                var x = event.getX(pointerIndex)
                var y = event.getY(pointerIndex)

                // Führe Panning nur dann durch, wenn das Event sicher kein Klick ist. Das verhindert kleine
                // Bewegungen des Bildes beim Klicken.
                if (touchCouldBeClick) {
                    return
                }

                // Wenn wir gerade Zoomen, richtet sich das Panning nach dem ScaleFocus
                if (panLastTouchIsScaleFocus && !SGD.isInProgress) {
                        // Zoomvorgang beendet, also wieder nach einzelnem Finger pannen
                        panLastTouchIsScaleFocus = false
                        panLastTouchX = x
                        panLastTouchY = y
                } else {
                    if (panLastTouchIsScaleFocus) {
                        x = SGD.focusX
                        y = SGD.focusY
                    }

                    // Bewegungsdistanz errechnen
                    val dx = x - panLastTouchX
                    val dy = y - panLastTouchY

                    // Aktuelle Position für nächstes move-Event merken
                    panLastTouchX = x
                    panLastTouchY = y

                    // Nur, falls wir gerade keinen laufenden Drag-Vorgang haben, das Panning tatsächlich ändern
                    if (!isCurrentlyDragging) {
                        // Jetzt pannen wir "offiziell" (ab jetzt keine Drag-Vorgänge mehr starten)
                        panActive = true

                        // Falls wir noch keine Pan-Werte haben (?) initialisiere sie mit 0
                        if (java.lang.Float.isNaN(panCenterX)) panCenterX = 0f
                        if (java.lang.Float.isNaN(panCenterY)) panCenterY = 0f

                        // Panning Geschwindigkeit wird an die aktuelle Skalierung angepasst
                        panCenterX -= dx / zoomScale
                        panCenterY -= dy / zoomScale

                        // Event auslösen, dass Pan/Zoom durch Touchevent verändert wurden
                        onTouchPanZoomChange()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // Der letzte Finger wird gehoben
                panActivePointerId = -1
                panActive = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
            }
            MotionEvent.ACTION_POINTER_UP -> {

                // Ein Finger verlässt das Touchscreen, aber es sind noch Finger auf dem Touchscreen.

                // Welcher Finger wurde entfernt?
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == panActivePointerId) {
                    // der "aktive" Finger wurde entfernt, wähle neuen und passe gemerkte Koordinaten an
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    panLastTouchX = event.getX(newPointerIndex)
                    panLastTouchY = event.getY(newPointerIndex)
                    panActivePointerId = event.getPointerId(newPointerIndex)
                }
            }
        }

        // Behandlung vom Zoomen
        if (!isCurrentlyDragging) {
            SGD.onTouchEvent(event)
        }

        // Position/Skalierung der ImageView anpassen
        update()
    }

    // ////// SCALELISTENER: implementiert die onScale-Methode des SGD und kümmert sich damit um den Zoom.
    private inner class ScaleListener : SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Wenn wir scalen, haben wir definitiv kein Klick-Event mehr.
            touchCouldBeClick = false

            // Damit das Panning während des Zooms sich nicht nur auf einen
            // Finger beschränkt, panne hier anhand des Focus-Punktes.
            if (!panLastTouchIsScaleFocus) {
                panLastTouchX = detector.focusX
                panLastTouchY = detector.focusY
                panLastTouchIsScaleFocus = true
            }

            // Zoom-Level aktualisieren
            val oldScale = zoomScale
            var scaleFactor = detector.scaleFactor
            zoomScale *= scaleFactor

            // Zoom-Level darf Minimum und Maximum nicht unter-/überschreiten
            if (zoomScale < minZoomScale || zoomScale > maxZoomScale) {
                zoomScale = Math.max(minZoomScale, Math.min(zoomScale, maxZoomScale))

                // scaleFactor wird unten noch mal benötigt, also anpassen
                scaleFactor = zoomScale / oldScale
            }

            // Um den Zoom-Focus (der Mittelpunkt zwischen den Fingern) beizubehalten, ist eine
            // zusätzliche Translation erforderlich.
            // (Vermeide Pivot-Parameter von Matrix.setScale(), da dies mit der Translation durchs
            // Panning kollidiert... so ist es einfacher.)

            // Berechne Fokus-Koordinaten relativ zum Pan-Center und zur Zoomscale
            val focusX = (detector.focusX - width / 2) / zoomScale
            val focusY = (detector.focusY - height / 2) / zoomScale

            // Durch Zoom wird Focus verschoben, hierdurch wird die Verschiebung rückgängig gemacht.
            val dx = focusX * (1 - scaleFactor)
            val dy = focusY * (1 - scaleFactor)
            if (java.lang.Float.isNaN(panCenterX)) panCenterX = 0f
            if (java.lang.Float.isNaN(panCenterY)) panCenterY = 0f

            // Verschiebe das Panning
            panCenterX -= dx
            panCenterY -= dy

            // SampleSize neuberechnen
            sampleSize = calculateSampleSize(zoomScale)

            // Event auslösen, dass Pan/Zoom durch Touchevent verändert wurden
            onTouchPanZoomChange()
            return true
        }
    }

    // ////// CLICK DETECTION
    private val gestureDetector = GestureDetector(context,
            object : SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!touchCouldBeClick) return
                    performLongClick()
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    return touchCouldBeClick && performClick()
                }
            })

    /**
     * Führt die Klickerkennung durch. Wird von onTouchEvent aufgerufen, welches nur dann weitermacht, wenn hier false
     * zurückgegeben wird (d.h. wenn kein Klick detektiert wurde).
     * Die Klickerkennung wurde ausgelagert, damit Subklassen onTouchEvent überschreiben und damit das Panning
     * deaktivieren, aber dennoch per Aufruf von super.onTouchEvent_clickDetection() Klicks erkennen lassen können.
     */
    protected fun onTouchEvent_clickDetection(event: MotionEvent) {
        // Was für ein MotionEvent wurde detektiert?
        val action = event.actionMasked

        // Falls mehrere Finger das Touchscreen berühren, kann es kein Klick sein.
        if (event.pointerCount > 1) {
            touchCouldBeClick = false
            gestureDetector.onTouchEvent(event)
            return
        }

        // Position des Fingers ermitteln
        // final int pointerIndex = event.getActionIndex();
        val x = event.x
        val y = event.y
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Erste Berührung des Touchscreens: Speichere Startposition.
                // (Falls wir uns zu weit davon wegbewegen, wollen wir keinen Klick auslösen.)
                touchCouldBeClick = true
                touchStartX = x
                touchStartY = y
            }
            MotionEvent.ACTION_MOVE ->             // Bewegung der Finger während Berührung
                if (touchCouldBeClick) {
                    // Falls wir uns zu weit vom Startpunkt der Geste wegbewegen, ist es kein Klick mehr.
                    if (Math.abs(x - touchStartX) > TOUCH_CLICK_TOLERANCE || Math.abs(y - touchStartY) > TOUCH_CLICK_TOLERANCE) {
                        touchCouldBeClick = false
                    }
                }
        }
        gestureDetector.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        Log.d("LIV/performClick", "Click on screen position $touchStartX, $touchStartY detected!")

        // Prüfe, ob ein OverlayIcon angeklickt wurde und führe gegebenenfalls dessen onClick-Methode aus.
        for (icon in overlayIconList) {
            // Icon überspringen, falls es keine Hitbox hat
            if (icon.touchHitbox == null) continue

            // Bildschirmposition des Icons (ohne Offset) errechnen
            val screenPoint = imageToScreenPosition(icon.imagePositionX.toFloat(), icon.imagePositionY.toFloat())

            // Position des Klicks relativ zum Icon
            val relativeX = (touchStartX - screenPoint.x).toInt()
            val relativeY = (touchStartY - screenPoint.y).toInt()

            // war der Klick innerhalb der Icon-Hitbox?
            if (icon.touchHitbox?.contains(relativeX, relativeY) ?: false) {
                // Falls eine Drag-Bewegung gestartet wurde, muss diese abgebrochen werden.
                if (icon.dragPointerID > -1) {
                    icon.onDragUp(touchStartX, touchStartY)
                }

                // Führe onClick-Event aus. Return, falls onClick das Event behandelt hat.
                if (icon.onClick(touchStartX, touchStartY)) return true
            }
        }

        // onClickPosition-Event auslösen. Falls es true zurückgibt, wurde das Event behandelt...
        return if (onClickPosition(touchStartX, touchStartY)) true else super.performClick()

        // ... ansonsten Standard-Handler ausführen, der dann andere onClick-Events triggert.
    }
    // ////// OVERLAY ICON DRAG AND DROP
    /**
     * Führt die Erkennung von Drag- und Drop-Events von OverlayIcons durch. Wird von onTouchEvent aufgerufen, welches
     * nur dann weitermacht, wenn hier false zurückgegeben wird (d.h. wenn für den aktuellen Finger kein Drag and Drop
     * detektiert wurde).
     */
    private fun onTouchEvent_dragAndDrop(event: MotionEvent): Boolean {
        // Was für ein MotionEvent wurde detektiert?
        val action = event.actionMasked

        // Position des ersten Pointers (Fingers) ermitteln
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val pointerID = event.getPointerId(pointerIndex)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Ein (erster oder weiterer) Finger berührt das Touchscreen

                // Drag and Drop nur dann, wenn wir gerade nicht mitten beim Panning oder Zooming sind.
                if (panActive) {
                    return false
                }

                // Prüfe, ob ein OverlayIcon berührt wurde und trigger ggf. dessen onDragDown-Event.
                for (icon in overlayIconList) {
                    // Icon überspringen, falls es keine Hitbox hat
                    if (icon.touchHitbox == null) continue

                    // Bildschirmposition des Icons (ohne Offset) errechnen
                    val screenPoint = imageToScreenPosition(icon.imagePositionX.toFloat(), icon.imagePositionY.toFloat())

                    // Position des Klicks relativ zum Icon
                    val relativeX = (x - screenPoint.x).toInt()
                    val relativeY = (y - screenPoint.y).toInt()

                    // war der Klick innerhalb der Icon-Hitbox?
                    if (icon.touchHitbox?.contains(relativeX, relativeY) ?: false) {
                        // Ja, führe onDragDown-Event aus. Return, falls onClick das Event behandelt hat.
                        if (icon.onDragDown(pointerID, x, y)) {
                            // Merken, dass ein Drag-Vorgang läuft (Extra-Test, da onDragDown() z.B. auch laufende
                            // Drag-Vorgänge abbrechen könnte (gilt als behandeltes Event))
                            if (icon.dragPointerID != -1) {
                                isCurrentlyDragging = true
                            }
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Bewegung eines Fingers während Berührung

                // Drag and Drop nur dann, wenn das Event definitiv kein Klick ist.
                if (touchCouldBeClick) {
                    return false
                }

                // Falls gerade gar kein Drag-Vorgang läuft, können wir hier auch abbrechen
                if (!isCurrentlyDragging) {
                    return false
                }
                var handledDragAndDrop = false

                // Prüfe für jeden Pointer (=Finger)...
                var i = 0
                while (i < event.pointerCount) {

                    // ... und für jedes OverlayIcon...
                    for (icon in overlayIconList) {
                        // ... ob der Finger das OverlayIcon draggt...
                        // (Falls nicht gedraggt, gibt die Methode -1 zurück)
                        if (icon.dragPointerID == event.getPointerId(i)) {
                            // ... falls ja, führe onDragMove-Event aus
                            if (icon.onDragMove(event.getX(i), event.getY(i))) handledDragAndDrop = true
                        }
                    }
                    i++
                }
                // true zurückgeben, falls wir mindestens ein Drag- and Drop-Event gehandlet haben
                return handledDragAndDrop
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // Ein Finger verlässt das Touchscreen

                // Falls gerade gar kein Drag-Vorgang läuft, können wir hier auch abbrechen
                if (!isCurrentlyDragging) {
                    return false
                }

                // Counter, wie viele Icons (im Fall von Multitouch) noch gedraggt werden.
                var stillDraggedCount = 0
                var returnvalue = false

                // Prüfe, ob der Finger eines der OverlayIcons draggt und trigger ggf. dessen onDragUp-Event.
                for (icon in overlayIconList) {
                    // Wird das Icon gerade gedraggt?
                    if (icon.dragPointerID != -1) {
                        stillDraggedCount++

                        // Wird es vom aktuellen Finger gedraggt?
                        if (icon.dragPointerID == pointerID) {
                            // Ja, führe onDragUp-Event aus. (Ignoriere return value)
                            icon.onDragUp(x, y)
                            stillDraggedCount--
                            returnvalue = true
                        }
                    }
                }

                // Falls nun kein Icon mehr gedraggt wird, Variable zurücksetzen
                if (stillDraggedCount == 0) {
                    isCurrentlyDragging = false
                }
                if (returnvalue) return true
            }
        }

        // Event wurde nicht behandelt, reiche es an (den Rest von) onTouchEvent weiter.
        return false
    }

    /**
     * Bricht alle laufenden Dragvorgänge ab.
     */
    fun cancelAllDragging() {
        for (icon in overlayIconList) {
            // Wird das Icon gerade gedraggt?
            if (icon.dragPointerID != -1) {
                // Drag-Vorgang abbrechen
                icon.onDragUp(Float.NaN, Float.NaN)
            }
        }
        isCurrentlyDragging = false
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// DARSTELLUNG DES BILDES
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Aktualisiert die Darstellung. Wird aufgerufen, wenn Pan-Position oder Zoomlevel verändert werden.
     */
    open fun update() {
        // Begrenze das Panning, so dass man das Bild nicht beliebig weit aus der Bildfläche schieben kann.
        if (imageWidth > 0 && imageHeight > 0) {
            // Panning so begrenzen, dass PanCenter nicht die Bildgrenzen verlassen kann. (Simple, huh?)
            panCenterX = Math.min(Math.max(panCenterX, 0f), rotatedImageWidth.toFloat())
            panCenterY = Math.min(Math.max(panCenterY, 0f), rotatedImageHeight.toFloat())
        }

        // View neu zeichnen lassen (onDraw)
        // TODO ausprobieren, ob mehrere invalidate-Aufrufe in Folge onDraw auch mehrfach aufrufen
        // .... (da manche Methoden implizit mehrmals update() aufrufen :S)
        this.invalidate()
    }

    /**
     * Returns true if everything is ready to call onDraw (pan set, getWidth/Height return non-zero values, etc.).
     * Check this if you override onDraw!
     */
    protected val isReadyToDraw: Boolean
        get() = !java.lang.Float.isNaN(panCenterX) && !java.lang.Float.isNaN(panCenterY) && width != 0 && height != 0

    /**
     * Zeichnet das Bild, gegebenenfalls in Einzelteilen, sowie die OverlayIcons.
     * Siehe auch [.onDraw_cachedImage], [.onDraw_staticBitmap],
     * [.onDraw_overlayIcons].
     */
    override fun onDraw(canvas: Canvas) {
        if (!isReadyToDraw) {
            return
        }

        // ZoomScale darf nicht 0 sein -- wird es eigentlich auch nie, aber für den Fall der Fälle...
        if (zoomScale == 0f) {
            zoomScale = 1.0.toFloat() / 256 // dürfte klein genug sein :P
        }
        var translateX = -panCenterX
        var translateY = -panCenterY
        when ((rotation + 45) % 360 / 90) {
            0 -> {
            }
            1 -> translateX += imageHeight.toFloat()
            2 -> {
                translateX += imageWidth.toFloat()
                translateY += imageHeight.toFloat()
            }
            3 -> translateY += imageWidth.toFloat()
        }
        screenToImageMatrix.setRotate(-rotation.toFloat())
        screenToImageMatrix.preTranslate(-translateX, -translateY)
        screenToImageMatrix.preScale(1.0f / zoomScale, 1.0f / zoomScale)
        screenToImageMatrix.preTranslate((-width / 2).toFloat(), (-height / 2).toFloat())
        imageToScreenMatrix.setTranslate((width / 2).toFloat(), (height / 2).toFloat())
        imageToScreenMatrix.preScale(zoomScale, zoomScale)
        imageToScreenMatrix.preTranslate(translateX, translateY)
        imageToScreenMatrix.preRotate(rotation.toFloat())
        sampledImageToScreenMatrix.setTranslate((width / 2).toFloat(), (height / 2).toFloat())
        sampledImageToScreenMatrix.preScale(sampleSize * zoomScale, sampleSize * zoomScale)
        sampledImageToScreenMatrix.preTranslate(translateX / sampleSize, translateY / sampleSize)
        sampledImageToScreenMatrix.preRotate(rotation.toFloat())
        panCenterMatrix.setTranslate(translateX + panCenterX, translateY + panCenterY)
        panCenterMatrix.preRotate(rotation.toFloat())

        // Prüfe, ob wir ein CachedImage oder ein statisches Bitmap verwenden
        if (cachedImage != null) {
            onDraw_cachedImage(canvas)
        } else {
            if (!onDraw_staticBitmap(canvas)) {
                // Fallback (setBackgroundAlpha funktioniert hiermit nicht... naja.)
                super.onDraw(canvas)
            }
        }

        // Overlay-Icons zeichnen
        onDraw_overlayIcons(canvas)
    }

    /**
     * Übernimmt den Teil von [.onDraw], der ein gecachtes Bild (in Tiles) anzeigt.
     */
    private fun onDraw_cachedImage(canvas: Canvas) {
        canvas.save()
        canvas.setMatrix(sampledImageToScreenMatrix)

        // Draw whole image, but quickly skip parts not actually visible
        // Somewhat less efficient than calculating the start and end coordinates
        // of the visible area, but the simplicity seems preferable.
        var y = 0
        while (y < imageHeight / sampleSize) {
            if (canvas.quickReject(0f, y.toFloat(), (imageWidth / sampleSize).toFloat(), (y + CachedImage.TILESIZE).toFloat(), Canvas.EdgeType.AA)) {
                y += CachedImage.TILESIZE
                continue
            }
            var x = 0
            while (x < imageWidth / sampleSize) {
                if (canvas.quickReject(x.toFloat(), y.toFloat(), (x + CachedImage.TILESIZE).toFloat(), (y + CachedImage.TILESIZE).toFloat(), Canvas.EdgeType.AA)) {
                    x += CachedImage.TILESIZE
                    continue
                }
                // Unsere Koordinaten sind abhängig vom Sampling. Das gesuchte Tile beginnt also nicht
                // bei (x,y) sondern bei samplingLevel*(x,y), wird aber an (x,y) gezeichnet.
                val bm = cachedImage!!.getTileBitmap(sampleSize * x, sampleSize * y, sampleSize)

                // Log.d("LIV/onDraw_cachedImage", "Drawing tile " + getCacheKey(sampleSize * x, sampleSize * y,
                // sampleSize)
                // + (bm == null ? " ... null" : (" at " + x + "," + y)));

                // Tile zeichnen, falls es bereits existiert (also im Cache gefunden wurde)
                if (bm != null) {
                    canvas.drawBitmap(bm, x.toFloat(), y.toFloat(), bgAlphaPaint)
                }
                x += CachedImage.TILESIZE
            }
            y += CachedImage.TILESIZE
        }
        canvas.restore()
    }

    /**
     * Übernimmt den Teil von [.onDraw], der ein statisches (nicht gecachtes) Bild anzeigt.
     *
     * @param canvas
     * @return False, falls auch kein staticBitmap vorhanden ist... Benutze super.onDraw().
     */
    private fun onDraw_staticBitmap(canvas: Canvas): Boolean {
        if (staticBitmap == null) return false

        // Bitmap statisch anzeigen
        canvas.save()
        canvas.setMatrix(imageToScreenMatrix)
        canvas.drawBitmap(staticBitmap!!, 0f, 0f, bgAlphaPaint)
        canvas.restore()
        return true
    }

    /**
     * Übernimmt den Teil von [.onDraw], der die OverlayIcons zeichnet.
     */
    private fun onDraw_overlayIcons(canvas: Canvas) {
        // Nichts tun, falls keine Overlay Icons vorhanden
        if (overlayIconList.isEmpty()) return
        for (icon in overlayIconList) {
            // save und restore, um alle Icons einzeln zu verschieben
            canvas.save()

            // Translation für Icon berechnen
            var pos = floatArrayOf(icon.imagePositionX.toFloat(), icon.imagePositionY.toFloat())
            imageToScreenMatrix.mapPoints(pos)
            pos[0] += icon.imageOffsetX.toFloat()
            pos[1] += icon.imageOffsetY.toFloat()

            // Canvas verschieben und Icon zeichnen
            canvas.translate(pos[0], pos[1])
            icon.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        // ////// KEYS FÜR ZU SPEICHERNDE DATEN IM SAVEDINSTANCESTATE-BUNDLE
        // Key für das Parcelable, das super.onSaveInstanceState() zurückgibt
        private const val SAVEDVIEWPARCEL = "savedViewParcel"

        // Keys für Pan- und Zoomdaten
        private const val SAVEDPANX = "savedPanCenterX"
        private const val SAVEDPANY = "savedPanCenterY"
        private const val SAVEDZOOM = "savedZoomScale"

        // ////// CONSTANTS
        // Toleranz für Abweichung vom Startpunkt beim Klicken in px
        private const val TOUCH_CLICK_TOLERANCE = 6
        // ////// SAMPLESIZE UND MAX/MIN ZOOM SCALE BERECHNUNG
        /**
         * Berechnet die Sample-Stufe zu einer Zoom-Stufe. Dies ist dabei die größte Zweierpotenz, die <= 1/scale ist.
         *
         * @param scale Zoom-Stufe
         * @return Sample-Stufe
         */
        private fun calculateSampleSize(scale: Float): Int {
            // Begrenze Samplesize auf 32 (sollte ausreichen)
            var sample = 1
            while (sample < 32) {

                // Das Sampling Level ist die größte Zweierpotenz, die <= 1/scale ist.
                if (sample * scale > 0.5f) return sample
                sample *= 2
            }
            return 32
        }
    }
}