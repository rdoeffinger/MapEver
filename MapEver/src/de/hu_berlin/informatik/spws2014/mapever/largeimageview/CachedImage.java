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

package de.hu_berlin.informatik.spws2014.mapever.largeimageview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory.Options;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Handler;
import androidx.collection.LruCache;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Der LruCache-bezogene Code wurde in Anlehnung an folgendes Tutorial erstellt:
// http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html

class CachedImage extends LruCache<String, Bitmap> {

    interface CacheMissResolvedCallback {
        void onCacheMissResolved();
    }

    // ////// CONSTANTS

    // Tilegröße (Breite und Höhe, sollte Zweierpotenz sein)
    public static final int TILESIZE = 512;


    // ////// BITMAP, TILE AND CACHE STUFF

    // BitmapRegionDecoder (liest Bildausschnitte aus InputStreams)
    private BitmapRegionDecoder regionDecoder;
    private final List<BitmapRegionDecoder> perThreadRegionDecoders = new ArrayList<>();
    private final int maxTasks = Math.max(2, Runtime.getRuntime().availableProcessors());
    private final String file;

    // Liste für Keys der Tiles, die aktuell von TileWorkerTasks generiert werden
    private final List<String> workingTileTasks = new ArrayList<>();

    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    // Callback, wenn nach einem Cache-Miss das gesuchte Tile erzeugt und gecachet wurde.
    private final CacheMissResolvedCallback cacheMissResolvedCallback;


    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND INITIALIZATION
    // ////////////////////////////////////////////////////////////////////////

    /**
     * Initialisiert und erzeugt einen Tile-Cache als LRU-Cache.
     *
     * @param inputStream Stream zur Bilddatei (nur JPEG und PNG)
     * @param cacheCallback Callback, wenn ein Tile nach einem Cache-Miss generiert und im Cache gespeichert wurde.
     * @throws IOException Wird geworfen, wenn BitmapRegionDecoder nicht instanziiert werden kann (falls das Bild
     *             weder JPEG noch PNG ist, oder bei einem anderen IO-Fehler)
     */
    public CachedImage(InputStream inputStream, String file, CachedImage.CacheMissResolvedCallback cacheCallback) throws IOException {
        // Tilecache erzeugen durch Aufruf des LruCache<String, Bitmap>-Konstruktors
        super(calculateCacheSize());

        // Callback setzen
        cacheMissResolvedCallback = cacheCallback;

        // Prefer to use file name, as that allows additional
        // regionDecoder instances for better parallelism.
        this.file = file;
        if (file != null) inputStream = new FileInputStream(file);

        // BitmapRegionDecoder instanziieren. Wirft bei nicht unterstütztem Format (andere als JPEG und PNG)
        // eine IOException.
        regionDecoder = BitmapRegionDecoder.newInstance(inputStream, true);

        if (regionDecoder == null) {
            throw new IOException("BitmapRegionDecoder could not create instance for unknown reasons");
        }
    }

    // ////// LRUCACHE METHODS OVERRIDES

    /**
     * Größe eines Cache-Eintrags (Bitmap) in Kilobyte. Die Größe des Caches insgesamt wird also an der Menge der
     * Bitmapdaten statt an der Anzahl der Einträge gemessen.
     */
    @Override
    protected int sizeOf(String key, Bitmap bitmap) {
        // (getByteCount() (API 12) == getRowBytes() * getHeight())
        return (bitmap.getRowBytes() * bitmap.getHeight()) / 1024;
    }

    /**
     * Berechnet die optimale Cachegröße.
     */
    private static int calculateCacheSize() {
        // Get max available VM memory, exceeding this amount will throw an OutOfMemory exception.
        // Stored in kilobytes as LruCache takes an int in its constructor.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        Log.d("CachedImage/calculateCacheSize", "Memory max: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB, total: "
              + Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB, free: "
              + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB");

        // Use 1/8th of the available memory for this memory cache.
        // TODO Gute Wahl? Nein lieber abhängig von max-total(+free) machen, oder? (Und dann vielleicht ruhig
        // .... größer.) (Außerdem: Gerätabhängig? Hm :/) (Problem, wenn Cache nicht ausreicht und noch benötigte
        // .... Tiles sofort rausgeworfen werden... Hmmmmm.)
        // (mal 1/4 nehmen und gucken, wies damit so läuft)
        final int cacheSize = maxMemory / 4;

        Log.d("CachedImage/calculateCacheSize", "Max memory: " + maxMemory / 1024 + " MB, thus creating a cache of size "
              + cacheSize / 1024 + " MB");

        return cacheSize;
    }


    // ////////////////////////////////////////////////////////////////////////
    // //////////// IMAGE PROPERTIES
    // ////////////////////////////////////////////////////////////////////////

    /**
     * Gibt die Breite des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    public int getWidth() {
        return regionDecoder.getWidth();
    }

    /**
     * Gibt die Höhe des Bildes zurück. (Tatsächliche Bildgröße, auch wenn nur kleinere Teile geladen sind.)
     */
    public int getHeight() {
        return regionDecoder.getHeight();
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
    private static String getCacheKey(int x, int y, int sampleSize) {
        return x + "_" + y + "_" + sampleSize;
    }

    /**
     * Liefert Ausschnitt ab x, y mit Samplesize scale zurück, falls im Cache vorhanden, ansonsten null.
     *
     * @param x Linke Eckkoordinate.
     * @param y Obere Eckkoordinate.
     * @param samplingSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @return Tile-Bitmap oder null
     */
    private Bitmap getCachedTileBitmap(int x, int y, int samplingSize) {
        return get(getCacheKey(x, y, samplingSize));
    }

    /**
     * Speichert gegebenen Tile im Cache.
     *
     * @param x Linke Eckkoordinate.
     * @param y Obere Eckkoordinate.
     * @param sampleSize Samplesize (n ist 1/n mal so groß wie das Original)
     * @param tile Tile-Bitmap
     */
    private void putTileInCache(int x, int y, int sampleSize, Bitmap tile) {
        if (tile == null) {
            Log.e("CachedImage/putTileInCache", "tile == null, won't put into cache!");
            return;
        }

        // Key erzeugen
        String key = getCacheKey(x, y, sampleSize);

        Log.d("CachedImage/putTileInCache", "Putting tile " + key + " into cache.");

        // Tile im Cache speichern
        put(key, tile);
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
    private Bitmap generateTileBitmap(BitmapRegionDecoder decoder, int left, int top, int sampleSize) {
        // Key erzeugen
        String key = getCacheKey(left, top, sampleSize);

        // Kein neues Tile generieren, falls es bereits vorhanden ist.
        if (get(key) != null) {
            return null;
        }

        Log.d("CachedImage/generateTileBitmap", "Generating tile " + key + " ...");
        Log.d("CachedImage/generateTileBitmap", "Memory max: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB, total: "
              + Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB, free: "
              + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB");

        // TODO OutOfMemory-Exceptions auffangen, Cachegröße verkleinern? Oder so?

        // Wenn Tile komplett außerhalb des Bildbereichs liegt, gibt es kein Tile.
        // (< 0 statt < -TILESIZE reicht aus, da left,top % TILESIZE = 0 angenommen wird.)
        if (left < 0 || left >= getWidth() || top < 0 || top >= getHeight()) {
            return null;
        }

        // Berechne Maße/Eckpunkte des Tiles (gesampelte Tiles sollen dennoch TILESIZE groß sein, aber der gewünschte
        // Bildausschnitt wird dadurch natürlich größer, daher *sampleSize)
        // min(), um Tile am Rand abschneiden, wenn Bildrest nicht groß genug.
        int right = Math.min(getWidth(), left + sampleSize * TILESIZE);
        int bottom = Math.min(getHeight(), top + sampleSize * TILESIZE);

        // SampleSize festlegen, um großes Bild bei geringer Zoomstufe runterzuskalieren
        Options opts = new Options();
        opts.inSampleSize = sampleSize;

        // Tile generieren und zurückgeben
        return decoder.decodeRegion(new Rect(left, top, right, bottom), opts);
    }

    /**
     * Asynchroner Task, der ein Tile generiert und es anschließend im Cache speichert.
     */
    static class TileWorkerTask implements Runnable {
        private final Handler handler;
        private final WeakReference<CachedImage> parent;
        private final int x;
        private final int y;
        private final int sampleSize;
        private BitmapRegionDecoder decoder;
        private boolean isDecoderPerThread;

        TileWorkerTask(CachedImage parent, int x, int y, int sampleSize) {
            handler = new Handler();
            this.parent = new WeakReference<>(parent);
            this.x = x;
            this.y = y;
            this.sampleSize = sampleSize;

            // select decoder to use
            decoder = null;
            isDecoderPerThread = true;
            if (!parent.perThreadRegionDecoders.isEmpty()) {
                // pick a cached one
                decoder = parent.perThreadRegionDecoders.remove(0);
            } else if (parent.file != null) {
                // try creating a new one
                try {
                    decoder = BitmapRegionDecoder.newInstance(new FileInputStream(parent.file), false);
                } catch (IOException e)
                {}
            }
            if (decoder == null) {
                // use shared one
                decoder = parent.regionDecoder;
                isDecoderPerThread = false;
            }
        }

        @Override
        public void run() {
            // Tile generieren
            final Bitmap result = parent.get().generateTileBitmap(decoder, x, y, sampleSize);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (isDecoderPerThread) parent.get().perThreadRegionDecoders.add(decoder);

                    // Tile in Cache speichern falls ungleich null
                    parent.get().putTileInCache(x, y, sampleSize, result);

                    // bei Fertigstellung wird der Eintrag in workingTileTasks entfernt
                    parent.get().workingTileTasks.remove(getCacheKey(x, y, sampleSize));

                    // Callback aufrufen, das in der LargeImageView dann this.invalidated.
                    if (parent.get().cacheMissResolvedCallback != null) {
                        parent.get().cacheMissResolvedCallback.onCacheMissResolved();
                    }
                }
            });
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
    public Bitmap getTileBitmap(int x, int y, int sampleSize) {
        // Tile aus Cache laden, falls vorhanden, sonst null.
        Bitmap tile = getCachedTileBitmap(x, y, sampleSize);

        // Tile in asynchronen Task generieren, falls es nicht im Cache gefunden wurde.
        if (tile == null) {
            // Key erzeugen
            String key = getCacheKey(x, y, sampleSize);

            // Prüfe zunächst, ob dieser Tile bereits einen laufenden TileWorkerTask hat
            if (workingTileTasks.contains(key)) {
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " is already being generated...");

                // Ja, also kein Bild zurückgeben
                return null;
            } else if (workingTileTasks.size() >= maxTasks) {
                // Generate multiple tiles to take advantage of multicore, but limit to CPU count
                // to reduce memory usage and other possible issues (but minimum 2 for some parallelism).
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " not found in cache, but we're already generating a tile... Wait...");
                return null;
            } else {
                //Log.d("CachedImage/getTileBitmap", "Tile " + key + " not found in cache -> generating (async)...");
                // Starte Task
                TileWorkerTask task = new TileWorkerTask(this, x, y, sampleSize);
                threadPool.execute(task);

                // Wir merken uns, dass dieses Tile jetzt generiert wird, damit bei einem nächsten Aufruf vor der
                // Fertigstellung des Tiles nicht noch ein gleicher Task erzeugt wird.
                workingTileTasks.add(key);

                // null zurückgeben um zu signalisieren, dass NOCH kein Bild vorhanden ist.
                // TileWorkerTask veranlasst nach dem Laden ein invalidate();
                return null;
            }
        }

        return tile;
    }

}
