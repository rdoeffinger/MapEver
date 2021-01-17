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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import androidx.collection.LruCache
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.Executors

// Der LruCache-bezogene Code wurde in Anlehnung an folgendes Tutorial erstellt:
// http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html
internal class CachedImage(_inputStream: InputStream?, file: String?, cacheCallback: CacheMissResolvedCallback) : LruCache<String, Bitmap>(calculateCacheSize()) {
    internal interface CacheMissResolvedCallback {
        fun onCacheMissResolved()
    }

    // ////// BITMAP, TILE AND CACHE STUFF
    // BitmapRegionDecoder (liest Bildausschnitte aus InputStreams)
    private val regionDecoder: BitmapRegionDecoder
    private val perThreadRegionDecoders: MutableList<BitmapRegionDecoder?> = ArrayList()
    private val maxTasks = Math.max(2, Runtime.getRuntime().availableProcessors())
    private val file = file

    // Liste für Keys der Tiles, die aktuell von TileWorkerTasks generiert werden
    private val workingTileTasks: MutableList<String> = ArrayList()
    private val threadPool = Executors.newCachedThreadPool()

    // Callback, wenn nach einem Cache-Miss das gesuchte Tile erzeugt und gecachet wurde.
    private val cacheMissResolvedCallback: CacheMissResolvedCallback = cacheCallback
    // ////// LRUCACHE METHODS OVERRIDES
    /**
     * Größe eines Cache-Eintrags (Bitmap) in Kilobyte. Die Größe des Caches insgesamt wird also an der Menge der
     * Bitmapdaten statt an der Anzahl der Einträge gemessen.
     */
    protected override fun sizeOf(key: String, bitmap: Bitmap): Int {
        // (getByteCount() (API 12) == getRowBytes() * getHeight())
        return bitmap.rowBytes * bitmap.height / 1024
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// IMAGE PROPERTIES
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Gibt die Breite des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    val width: Int
        get() = regionDecoder.width

    /**
     * Gibt die Höhe des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    val height: Int
        get() = regionDecoder.height

    /**
     * Liefert Ausschnitt ab x, y mit Samplesize scale zurück, falls im Cache vorhanden, ansonsten null.
     *
     * @param x Linke Eckkoordinate.
     * @param y Obere Eckkoordinate.
     * @param samplingSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @return Tile-Bitmap oder null
     */
    private fun getCachedTileBitmap(x: Int, y: Int, samplingSize: Int): Bitmap? {
        return get(getCacheKey(x, y, samplingSize))
    }

    /**
     * Speichert gegebenen Tile im Cache.
     *
     * @param x Linke Eckkoordinate.
     * @param y Obere Eckkoordinate.
     * @param sampleSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @param tile Tile-Bitmap
     */
    private fun putTileInCache(x: Int, y: Int, sampleSize: Int, tile: Bitmap?) {
        if (tile == null) {
            Log.e("CachedImage/putTileInCache", "tile == null, won't put into cache!")
            return
        }

        // Key erzeugen
        val key = getCacheKey(x, y, sampleSize)
        Log.d("CachedImage/putTileInCache", "Putting tile $key into cache.")

        // Tile im Cache speichern
        put(key, tile)
    }

    /**
     * Generiert Bildausschnitt ab Koordinaten (left, top) mit sampleSize gibt ihn als Bitmap zurück.
     * Sollte nicht direkt aufgerufen werden, sondern asynchron über einen TileWorkerTask.
     *
     * @param left Linke Eckkoordinate.
     * @param top Obere Eckkoordinate.
     * @param sampleSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @return Bitmap des Tiles (maximal TILESIZE*TILESIZE Pixel groß)
     */
    private fun generateTileBitmap(decoder: BitmapRegionDecoder, left: Int, top: Int, sampleSize: Int): Bitmap? {
        // Key erzeugen
        val key = getCacheKey(left, top, sampleSize)

        // Kein neues Tile generieren, falls es bereits vorhanden ist.
        if (get(key) != null) {
            return null
        }
        Log.d("CachedImage/generateTileBitmap", "Generating tile $key ...")
        Log.d("CachedImage/generateTileBitmap", "Memory max: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB, total: "
                + Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB, free: "
                + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB")

        // TODO OutOfMemory-Exceptions auffangen, Cachegröße verkleinern? Oder so?

        // Wenn Tile komplett außerhalb des Bildbereichs liegt, gibt es kein Tile.
        // (< 0 statt < -TILESIZE reicht aus, da left,top % TILESIZE = 0 angenommen wird.)
        if (left < 0 || left >= width || top < 0 || top >= height) {
            return null
        }

        // Berechne Maße/Eckpunkte des Tiles (gesampelte Tiles sollen dennoch TILESIZE groß sein, aber der gewünschte
        // Bildausschnitt wird dadurch natürlich größer, daher *sampleSize)
        // min(), um Tile am Rand abschneiden, wenn Bildrest nicht groß genug.
        val right = Math.min(width, left + sampleSize * TILESIZE)
        val bottom = Math.min(height, top + sampleSize * TILESIZE)

        // SampleSize festlegen, um großes Bild bei geringer Zoomstufe runterzuskalieren
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sampleSize

        // Tile generieren und zurückgeben
        return decoder.decodeRegion(Rect(left, top, right, bottom), opts)
    }

    /**
     * Asynchroner Task, der ein Tile generiert und es anschließend im Cache speichert.
     */
    internal class TileWorkerTask(parent: CachedImage, x: Int, y: Int, sampleSize: Int) : Runnable {
        private val handler = Handler()
        private val parent = WeakReference(parent)
        private val x = x
        private val y = y
        private val sampleSize = sampleSize
        private val decoder: BitmapRegionDecoder
        private var isDecoderPerThread: Boolean
        override fun run() {
            // Tile generieren
            val result = parent.get()?.generateTileBitmap(decoder, x, y, sampleSize)
            handler.post {
                if (isDecoderPerThread) parent.get()?.perThreadRegionDecoders?.add(decoder)

                // Tile in Cache speichern falls ungleich null
                parent.get()?.putTileInCache(x, y, sampleSize, result)

                // bei Fertigstellung wird der Eintrag in workingTileTasks entfernt
                parent.get()?.workingTileTasks?.remove(getCacheKey(x, y, sampleSize))

                // Callback aufrufen, das in der LargeImageView dann this.invalidated.
                parent.get()?.cacheMissResolvedCallback?.onCacheMissResolved()
            }
        }

        init {
            // select decoder to use
            var d: BitmapRegionDecoder? = null
            isDecoderPerThread = true
            if (!parent.perThreadRegionDecoders.isEmpty()) {
                // pick a cached one
                d = parent.perThreadRegionDecoders.removeAt(0)
            } else if (parent.file != null) {
                // try creating a new one
                try {
                    d = BitmapRegionDecoder.newInstance(FileInputStream(parent.file), false)
                } catch (e: IOException) {
                }
            }
            if (d != null) {
                decoder = d
            } else {
                // use shared one
                decoder = parent.regionDecoder
                isDecoderPerThread = false
            }
        }
    }

    /**
     * Gibt den Ausschnitt des Bildes zurück, der bei x,y beginnt und TILESIZE breit und hoch ist, bzw. am Rand kleiner.
     * Tiles werden mit LRU gecachet (nach x, y, scale).
     *
     * @param x Linke Eckkoordinate.
     * @param y Obere Eckkoordinate.
     * @param sampleSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @return
     */
    fun getTileBitmap(x: Int, y: Int, sampleSize: Int): Bitmap? {
        // Tile aus Cache laden, falls vorhanden, sonst null.
        val tile = getCachedTileBitmap(x, y, sampleSize)

        // Tile in asynchronen Task generieren, falls es nicht im Cache gefunden wurde.
        if (tile == null) {
            // Key erzeugen
            val key = getCacheKey(x, y, sampleSize)

            // Prüfe zunächst, ob dieser Tile bereits einen laufenden TileWorkerTask hat
            return if (workingTileTasks.contains(key)) {
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " is already being generated...");

                // Ja, also kein Bild zurückgeben
                null
            } else if (workingTileTasks.size >= maxTasks) {
                // Generate multiple tiles to take advantage of multicore, but limit to CPU count
                // to reduce memory usage and other possible issues (but minimum 2 for some parallelism).
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " not found in cache, but we're already generating a tile... Wait...");
                null
            } else {
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " not found in cache -> generating (async)...");
                // Starte Task
                val task = TileWorkerTask(this, x, y, sampleSize)
                threadPool.execute(task)

                // Wir merken uns, dass dieses Tile jetzt generiert wird, damit bei einem nächsten Aufruf vor der
                // Fertigstellung des Tiles nicht noch ein gleicher Task erzeugt wird.
                workingTileTasks.add(key)

                // null zurückgeben um zu signalisieren, dass NOCH kein Bild vorhanden ist.
                // TileWorkerTask veranlasst nach dem Laden ein invalidate();
                null
            }
        }
        return tile
    }

    companion object {
        // ////// CONSTANTS
        // Tilegröße (Breite und Höhe, sollte Zweierpotenz sein)
        const val TILESIZE = 512

        /**
         * Berechnet die optimale Cachegröße.
         */
        private fun calculateCacheSize(): Int {
            // Get max available VM memory, exceeding this amount will throw an OutOfMemory exception.
            // Stored in kilobytes as LruCache takes an int in its constructor.
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            Log.d("CachedImage/calculateCacheSize", "Memory max: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB, total: "
                    + Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB, free: "
                    + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB")

            // Use 1/8th of the available memory for this memory cache.
            // TODO Gute Wahl? Nein lieber abhängig von max-total(+free) machen, oder? (Und dann vielleicht ruhig
            // .... größer.) (Außerdem: Gerätabhängig? Hm :/) (Problem, wenn Cache nicht ausreicht und noch benötigte
            // .... Tiles sofort rausgeworfen werden... Hmmmmm.)
            // (mal 1/4 nehmen und gucken, wies damit so läuft)
            val cacheSize = maxMemory / 4
            Log.d("CachedImage/calculateCacheSize", "Max memory: " + maxMemory / 1024 + " MB, thus creating a cache of size "
                    + cacheSize / 1024 + " MB")
            return cacheSize
        }
        // ////////////////////////////////////////////////////////////////////////
        // //////////// VERWALTUNG DES BILDAUSSCHNITT-CACHES
        // ////////////////////////////////////////////////////////////////////////
        /**
         * Generiert aus x, y, scale den Cachekey (x_y_sampleSize).
         *
         * @param x Linke Eckkoordinate.
         * @param y Obere Eckkoordinate.
         * @param sampleSize Samplesize (n ist 1/n mal so groß wie das Original)
         * @return String x+"_"+y+"_"+sampleSize
         */
        private fun getCacheKey(x: Int, y: Int, sampleSize: Int): String {
            return x.toString() + "_" + y + "_" + sampleSize
        }
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND INITIALIZATION
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Initialisiert und erzeugt einen Tile-Cache als LRU-Cache.
     *
     * @param inputStream Stream zur Bilddatei (nur JPEG und PNG)
     * @param cacheCallback Callback, wenn ein Tile nach einem Cache-Miss generiert und im Cache gespeichert wurde.
     * @throws IOException Wird geworfen, wenn BitmapRegionDecoder nicht instanziiert werden kann (falls das Bild
     * weder JPEG noch PNG ist, oder bei einem anderen IO-Fehler)
     */
    init {
        // Tilecache erzeugen durch Aufruf des LruCache<String, Bitmap>-Konstruktors
        var inputStream = _inputStream

        // Prefer to use file name, as that allows additional
        // regionDecoder instances for better parallelism.
        if (file != null) inputStream = FileInputStream(file)

        // BitmapRegionDecoder instanziieren. Wirft bei nicht unterstütztem Format (andere als JPEG und PNG)
        // eine IOException.
        var d: BitmapRegionDecoder? = BitmapRegionDecoder.newInstance(inputStream, true)
        if (d == null) {
            throw IOException("BitmapRegionDecoder could not create instance for unknown reasons")
        }
        regionDecoder = d!!
    }
}