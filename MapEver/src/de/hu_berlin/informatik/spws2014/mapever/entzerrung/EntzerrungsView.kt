/* Copyright (C) 2014,2015 Jan Müller, Björn Stelter
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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import de.hu_berlin.informatik.spws2014.mapever.R
import de.hu_berlin.informatik.spws2014.mapever.largeimageview.LargeImageView
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.*
import kotlin.math.max

class EntzerrungsView : LargeImageView {
    // ////// PRIVATE MEMBERS
    // Activity context
    var entzerren: Entzerren? = null

    // InputStream zum Bild
    private var imageFile: File? = null

    /**
     * Returns true, if image type is (hopefully?) supported by the deskewing algorithm (not for GIF, for example).
     */
    var isImageTypeSupported = true
        private set

    // Eckpunkte als OverlayIcons
    private val corners = Array(CORNERS_COUNT) { CornerIcon(this) }

    /**
     * Returns true if corners are shown. (Image shall not be rectified if corners are hidden.)
     */
    // Zustandsvariablen
    var isShowingCorners = true
        private set
    private var punkte_gesetzt = false

    // Some objects for onDraw
    private val white = Paint()
    private val wallpath = Path()

    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND INITIALIZATION
    // ////////////////////////////////////////////////////////////////////////
    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    /**
     * Initializes EntzerrungsView (called by constructor), sets background color, image transparency and creates
     * corner icons (each at 0,0).
     */
    private fun init() {
        // nichts weiter tun, wenn die View in Eclipse's GUI-Editor angezeigt wird
        if (this.isInEditMode) return

        // Set transparency of LIV image
        setForegroundAlpha(PICTURE_TRANSPARENT)

        // Paint for background: white square to highlight selected part of the map
        white.color = Color.WHITE
        white.style = Paint.Style.FILL
    }

    override fun onSaveInstanceState(): Parcelable {
        // LargeImageView gibt uns ein Bundle, in dem z.B. die Pan-Daten stecken. Verwende dieses als Basis.
        val bundle = super.onSaveInstanceState() as Bundle?

        // Speichere: "sollen die Ecken angezeigt (= das Bild entzerrt) werden?"
        bundle!!.putBoolean(SAVEDSHOWCORNERS, isShowingCorners)

        // Speichere Positionen der 4 Eckpunkte
        val cornerPoints = Array(CORNERS_COUNT) { i -> corners[i].position }
        bundle.putSerializable(SAVEDCORNERS, cornerPoints)

        // Speichere, ob Dateityp von den Algorithmen unterstützt wird (GIF z.B. nicht)
        bundle.putBoolean(SAVEDIMAGETYPESUPPORT, isImageTypeSupported)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val bundle = state as Bundle

        // Lade: "sollen die Ecken angezeigt (= das Bild entzerrt) werden?"
        val _show_corners = bundle.getBoolean(SAVEDSHOWCORNERS)
        showCorners(_show_corners)

        // Lade Positionen der 4 Eckpunkte
        val cornerPoints = (bundle.getSerializable(SAVEDCORNERS) as Array<Point>?)!!
        for (i in 0 until CORNERS_COUNT) {
            corners[i].position = cornerPoints[i]
        }
        punkte_gesetzt = true

        // Lade, ob Dateityp von den Algorithmen unterstützt wird (GIF z.B. nicht)
        isImageTypeSupported = bundle.getBoolean(SAVEDIMAGETYPESUPPORT)

        // Im Bundle stecken noch Informationen von LargeImageView, z.B. Pan-Daten. Reiche das Bundle also weiter.
        super.onRestoreInstanceState(bundle) // calls update()
    }

    /**
     * Lädt Bild in die EntzerrungsView. (Benutze dies anstelle von setImage...().)
     */
    @Throws(FileNotFoundException::class)
    fun loadImage(_imageFile: File) {
        // Image-File merken
        imageFile = _imageFile

        // Bild laden
        setImageFilename(imageFile!!.absolutePath)
    }

    /**
     * Erzeugt eine Bitmap aus dem geladenen Bild mit einer angegebenen SampleSize.
     */
    fun getSampledBitmap(_sampleSize: Int): Bitmap? {
        // Minimum SampleSize 1
        val sampleSize = max(1, _sampleSize)

        if (imageFile == null) {
            Log.w("EntzerrungsView/getSampledBitmap", "imageStream == null")
            return null
        }

        // Stream erzeugen
        val imageStream: InputStream
        imageStream = try {
            // Sollte eigentlich nie schiefgehen...
            FileInputStream(imageFile)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return null
        }

        // sampled bitmap dekodieren
        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        Log.d("EntzerrungsView/getSampledBitmap", "Decoding stream with sample size $sampleSize...")
        return BitmapFactory.decodeStream(imageStream, null, options)
    }// Skaliertes Bitmap erzeugen// Bestimmte optimale Auflösung... Vorerst: fixes Maximum für Höhe und Breite
    // TODO sinnvollere Lösung? bessere Konstanten? -> #230

    // SampleSize berechnen, maximales Verhältnis zwischen originaler und optimaler Auflösung

    /**
     * Nach dem Laden des Bildes werden die Ecken per Corner Detection ermittelt.
     */
    override fun onPostLoadImage(calledByOnSizeChanged: Boolean) {
        // LIV: Calculate zoom scale limits and stuff
        super.onPostLoadImage(calledByOnSizeChanged)
        if (width != 0 && height != 0) {
            if (!punkte_gesetzt) {
                calcCornerDefaults()
            }
        }
    }

    /**
     * Zeichnet das Bild mittels LargeImageView und stellt das helle Entzerrungsrechteck dar.
     */
    override fun onDraw(canvas: Canvas) {
        // nichts weiter tun, wenn die View in Eclipse's GUI-Editor angezeigt wird
        if (this.isInEditMode) {
            return
        }

        // check if we are ready to draw (getWidth and getHeight return non-zero values etc.)
        if (!isReadyToDraw) {
            return
        }
        if (isShowingCorners) {
            wallpath.reset()

            // Bildschirmkoordinaten der Punkte ermitteln
            // (Ecken sind bereits sortiert)
            for (i in 0 until CORNERS_COUNT) {
                val canvasPoint = corners[i].screenPosition
                if (i == 0) wallpath.moveTo(canvasPoint.x, canvasPoint.y) else wallpath.lineTo(canvasPoint.x, canvasPoint.y)
            }
            canvas.drawPath(wallpath, white)
        }

        // Bild per LargeImageView anzeigen
        super.onDraw(canvas)
    }

    /**
     * Behandlung von Touchevents.
     */
    // Lint-Warnung "EntzerrungsView overrides onTouchEvent but not performClick", obwohl sich super.onTouchEvent()
    // um Aufruf von performClick() kümmert.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (entzerren!!.isLoadingActive) return false

        // Zeigen wir die Schnellhilfe an?
        if (entzerren!!.isInQuickHelp) {
            // Hier kein Pan/Zoom, aber Klicks sollen die Hilfe beenden.
            onTouchEvent_clickDetection(event)
            return true
        }

        // Alle weiteren Touch-Events werden von der LargeImageView sowie anderen EventHandlers behandelt.
        // (Siehe z.B. CornerIcon für das Verschieben der Eckpunkte.)

        // Führe Defaulthandler aus (der Klicks, Pan und Zoom behandelt)
        return super.onTouchEvent(event)
    }

    public override fun onClickPosition(clickX: Float, clickY: Float): Boolean {
        // Schnellhilfe deaktivieren, falls aktiv
        if (entzerren!!.isInQuickHelp) {
            entzerren!!.endQuickHelp()
            return true
        }

        // Kein Klickevent behandelt
        return false
    }

    /**
     * Calculates default coordinates for corners (20%/80% of screen size).
     */
    fun calcCornerDefaults() {
        // Retrieve image dimensions
        val bitmap_breite = imageWidth
        val bitmap_hoehe = imageHeight

        // // Retrieve view dimensions
        // int view_breite = this.getWidth();
        // int view_hoehe = this.getHeight();
        //
        // // Choose the smaller one of each
        // int breite = Math.min(bitmap_breite, view_breite);
        // int hoehe = Math.min(bitmap_hoehe, view_hoehe);
        Log.d("EntzerrungsView/calcCornerDefaults", "breite/hoehe: $bitmap_breite, $bitmap_hoehe")

        // Take 20% of it
        val breite_scaled = (0.2 * bitmap_breite).toInt()
        val hoehe_scaled = (0.2 * bitmap_hoehe).toInt()

        // Set corner positions to 20% / 80% of the image or view size
        corners[0].position = Point(breite_scaled, hoehe_scaled)
        corners[1].position = Point(bitmap_breite - breite_scaled, hoehe_scaled)
        corners[2].position = Point(bitmap_breite - breite_scaled, bitmap_hoehe - hoehe_scaled)
        corners[3].position = Point(breite_scaled, bitmap_hoehe - hoehe_scaled)
        Log.d("EntzerrungsView/calcCornerDefaults", "Using default, breite/hoehe_scaled: $breite_scaled, $hoehe_scaled")
        punkte_gesetzt = true
    }

    /**
     * Shows or hides corners. (Image shall not be rectified if corners are hidden.)
     *
     * @param show
     */
    fun showCorners(show: Boolean) {
        val my_button = entzerren!!.findViewById<View>(R.id.entzerrung_ok_button) as ImageButton

        // OverlayIcons verstecken, falls keine Ecken angezeigt werden sollen
        corners.forEach { it.setVisibility(show) }

        // Bild des Buttons abhängig von der Aktion machen (mit (Crop) oder ohne (Done) Entzerrung?)
        // und Transparenz des Bildes setzen (Visualisierung des Entzerrungsvierecks)
        if (show) {
            my_button.setImageResource(R.drawable.ic_action_crop)
            setForegroundAlpha(PICTURE_TRANSPARENT)
        } else {
            my_button.setImageResource(R.drawable.ic_action_done)
            setForegroundAlpha(PICTURE_OPAQUE)
        }
        isShowingCorners = show
        update()
    }

    /**
     * Eckpunkte sortieren, um "sinnvolles" Rechteck anzuzeigen. (Wird von CornerIcon.onDragMove() aufgerufen.)
     */
    fun sortCorners() {
        // Sortiere Ecken erstmal nach y-Koordinate, d.h. Ecke 1 und 2 sind die beiden mit höchsten y-Koordinaten
        Arrays.sort(corners) { lhs, rhs -> // return <0 for lhs<rhs, =0 for lhs=rhs, >0 for lhs>rhs
            lhs.position.y - rhs.position.y
        }

        // -- Ecke 1 (links oben): die linkeste Ecke der zwei obersten Ecken
        // -- Ecke 2 (rechts oben): die andere Ecke der zwei obersten Ecken
        if (corners[0].position.x > corners[1].position.x) {
            // Swap linkere obere und rechte obere Ecke
            val tmp = corners[0]
            corners[0] = corners[1]
            corners[1] = tmp
        }

        // -- Ecke 3 (rechts unten): die rechte Ecke der verbleibenden
        // -- Ecke 4 (links unten): die verbleibende Ecke
        if (corners[2].position.x < corners[3].position.x) {
            // Swap rechte untere und linke untere Ecke
            val tmp = corners[2]
            corners[2] = corners[3]
            corners[3] = tmp
        }
    }

    /**
     * Gibt ein float[8] zurück mit x- und y-Koordinaten der Eckpunkte in Reihe (x1, y1, ..., x4, y4).
     *
     * @param _sampleSize Koordinaten werden angepasst (durch sampleSize dividiert), mindestens 1.
     * @return float[8] {x1, y1, x2, y2, x3, y3, x4, y4}
     */
    fun getPointOffsets(_sampleSize: Int): FloatArray {
        val sampleSize = max(1, _sampleSize)

        // Array für Koordinaten erzeugen
        val my_f = FloatArray(2 * CORNERS_COUNT)
        for (i in 0 until CORNERS_COUNT) {
            my_f[i * 2] = (corners[i].imagePositionX / sampleSize).toFloat()
            my_f[i * 2 + 1] = (corners[i].imagePositionY / sampleSize).toFloat()
        }
        return my_f
    }

    companion object {
        // ////// KEYS FÜR ZU SPEICHERNDE DATEN IM SAVEDINSTANCESTATE-BUNDLE
        private const val SAVEDSHOWCORNERS = "SAVEDSHOWCORNERS"
        private const val SAVEDCORNERS = "SAVEDCORNERS"
        private const val SAVEDIMAGETYPESUPPORT = "SAVEDIMAGETYPESUPPORT"

        // ////// OTHER CONSTANTS
        private const val PICTURE_TRANSPARENT = 200
        private const val PICTURE_OPAQUE = 255

        // Count of rectangle corners
        private const val CORNERS_COUNT = 4
    }
}
