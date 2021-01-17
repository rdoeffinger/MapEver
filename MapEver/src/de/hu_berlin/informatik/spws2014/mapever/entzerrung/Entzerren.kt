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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import de.hu_berlin.informatik.spws2014.mapever.BaseActivity
import de.hu_berlin.informatik.spws2014.mapever.MapEverApp
import de.hu_berlin.informatik.spws2014.mapever.R
import de.hu_berlin.informatik.spws2014.mapever.Start
import de.hu_berlin.informatik.spws2014.mapever.navigation.Navigation
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference

class Entzerren : BaseActivity() {
    // other constants
    private val INPUTFILENAME = cacheDir.toString() + "/" + MapEverApp.TEMP_IMAGE_FILENAME
    private val INPUTFILENAMEBAK = INPUTFILENAME + "_bak"

    // View references
    private var entzerrungsView: EntzerrungsView? = null
    private var layoutFrame: FrameLayout? = null

    // various state variables
    private var entzerrt = false // ist mindestens einmal entzerrt worden?
    var isInQuickHelp = false // Quickhelp aktiv?
        private set
    var isLoadingActive = false // Entzerrungsvorgang aktiv?
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entzerren)

        // Layout aufbauen
        layoutFrame = FrameLayout(baseContext)
        setContentView(layoutFrame)
        layoutInflater.inflate(R.layout.activity_entzerren, layoutFrame)

        // Referenz auf EntzerrungsView speichern
        entzerrungsView = findViewById<View>(R.id.entzerrungsview) as EntzerrungsView
        entzerrungsView!!.entzerren = this

        // Bild in die View laden
        loadImageFile()

        // Wird die Activity frisch neu erstellt oder haben wir einen gespeicherten Zustand?
        if (savedInstanceState == null) {
            // Verwende statischen Dateinamen als Eingabe
            // assert fails when coming from Camera
            //assert(intent.getStringExtra(Start.INTENT_IMAGEPATH) === INPUTFILENAME)
            val imageFile = File(INPUTFILENAME)
            val imageFile_bak = File(INPUTFILENAMEBAK)

            // Backup von der Datei erstellen, um ein Rückgängigmachen zu ermöglichen
            copy(imageFile, imageFile_bak)
        } else {
            // Zustandsvariablen wiederherstellen
            entzerrt = savedInstanceState.getBoolean(IMAGEENTZERRT)
            val _tutorial = savedInstanceState.getBoolean(SHOWHELPSAVED)
            if (_tutorial) {
                startQuickHelp()
            }
        }

        // EntzerrungsView updaten und neu zeichnen lassen
        entzerrungsView!!.update()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.entzerren, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (isLoadingActive) false else when (item.itemId) {
            R.id.action_quick_help -> {
                // Schnellhilfe-Button
                if (isInQuickHelp) {
                    endQuickHelp()
                } else {
                    startQuickHelp()
                }
                true
            }
            R.id.action_show_corners -> {
                // Toggle showCorners
                if (entzerrungsView!!.isShowingCorners) {
                    entzerrungsView!!.showCorners(false)
                } else if (entzerrungsView!!.isImageTypeSupported) {
                    entzerrungsView!!.showCorners(true)
                } else {
                    // Image type is not supported by deskewing algorithm (GIF?) so don't allow deskewing
                    showErrorMessage(R.string.deskewing_imagetype_not_supported)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        Log.d("Entzerren", "onSaveInstanceState...")

        // Save the current state
        savedInstanceState.putBoolean(SHOWHELPSAVED, isInQuickHelp)
        savedInstanceState.putBoolean(IMAGEENTZERRT, entzerrt)

        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onBackPressed() {
        if (isLoadingActive) return
        if (entzerrt) {
            // ersetze das Bild mit dem Backup
            val imageFile_bak = File(INPUTFILENAMEBAK)
            val imageFile = File(INPUTFILENAME)
            copy(imageFile_bak, imageFile)

            // Bild in die View laden
            loadImageFile()
            entzerrungsView!!.showCorners(true)
            entzerrungsView!!.calcCornerDefaults()
            entzerrt = false
            entzerrungsView!!.update()
        } else {
            // wenn nicht, gehe zum vorherigen Screen zurück
            // (nicht manuell die Start-Activity starten, einfach das onBackPressed gewöhnlich handlen lassen)
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("Entzerrung", "onPause...")

        // Gegebenenfalls laufende Drag-Operationen abbrechen
        if (entzerrungsView!!.isCurrentlyDragging) {
            entzerrungsView!!.cancelAllDragging()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClick_EntzerrungOk(v: View?) {
        if (isLoadingActive) return
        if (isInQuickHelp) {
            endQuickHelp()
            return
        }

        // wenn entzerrt werden soll...
        if (entzerrungsView!!.isShowingCorners) {
            // Bildschirm sperren
            lockScreenOrientation()
            startLoadingScreen()

            // Entzerrung in AsyncTask starten
            EntzerrenTask(this, INPUTFILENAME).execute()
        } else {
            // temp_bak löschen
            val imageFile_bak = File(INPUTFILENAMEBAK)
            if (imageFile_bak.exists()) {
                imageFile_bak.delete()
            }

            // Navigation mit Parameter loadmapid = -1 (== neue Karte) aufrufen
            val intent_nav = Intent(this, Navigation::class.java)
            intent_nav.putExtra(Navigation.INTENT_LOADMAPID, -1L)
            startActivity(intent_nav)
            finish()
        }
    }

    private fun startLoadingScreen() {
        if (!isLoadingActive) {
            isLoadingActive = true
            layoutInflater.inflate(R.layout.entzerren_loading, layoutFrame)
        }
    }

    private fun endLoadingScreen() {
        if (isLoadingActive) {
            isLoadingActive = false
            layoutFrame!!.removeViewAt(layoutFrame!!.childCount - 1)
        }
    }

    private fun startQuickHelp() {
        if (!isInQuickHelp) {
            isInQuickHelp = true
            layoutInflater.inflate(R.layout.entzerren_help, layoutFrame)
        }
    }

    fun endQuickHelp() {
        if (isInQuickHelp) {
            isInQuickHelp = false
            layoutFrame!!.removeViewAt(layoutFrame!!.childCount - 1)
        }
    }

    private fun showErrorMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorMessage(resID: Int) {
        showErrorMessage(resources.getString(resID))
    }

    private fun lockScreenOrientation() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val configuration = resources.configuration
        val rotation = windowManager.defaultDisplay.rotation

        // Search for the natural position of the device
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) ||
                configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)) {
            // Natural position is Landscape
            when (rotation) {
                Surface.ROTATION_0 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_90 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_180 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                Surface.ROTATION_270 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } else {
            // Natural position is Portrait
            when (rotation) {
                Surface.ROTATION_0 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Surface.ROTATION_90 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_180 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_270 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            }
        }
    }

    private fun unlockScreenOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun loadImageFile() {
        // Verwende statischen Dateinamen als Eingabe
        val imageFile = File(INPUTFILENAME)
        try {
            // Bild in die View laden
            entzerrungsView!!.loadImage(imageFile)
        } catch (e: FileNotFoundException) {
            showErrorMessage(R.string.error_filenotfound)
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            showErrorMessage(R.string.error_outofmemory)
            e.printStackTrace()
        }
    }

    // TODO mit neuem optimiertem Entzerrungsalgorithmus hinfällig...?
    private fun saveBitmap(bitmap: Bitmap, outFilename: String): Boolean {
        val outFile = File(outFilename)
        try {
            // Outputstream öffnen
            val outStream = FileOutputStream(outFile)

            // Bitmap komprimiert in Outputstream schreiben
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            outStream.close()
        } catch (e: IOException) {
            showErrorMessage(R.string.error_io)
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun copy(src: File, dst: File) {
        try {
            src.copyTo(dst)
        } catch (e: IOException) {
            showErrorMessage(R.string.error_io)
            e.printStackTrace()
        }
    }

    private class EntzerrenTask internal constructor(parent: Entzerren, fileName: String) : AsyncTask<Void?, Void?, String?>() {
        var entzerrtesBitmap: Bitmap? = null
        private val parent = WeakReference(parent)
        val fileName = fileName
        protected override fun doInBackground(vararg params: Void?): String? {
            var result: String? = null
            try {
                // TODO nach Möglichkeit ohne große Bitmaps (also streambasierte LargeImage-Version für JumbledImage)
                // .... (auch saveBitmap ersetzen)
                var sampleSize = 1

                // Beginne mit SampleSize 1 und skalier das Bild soweit runter, bis es nicht an einem OOM scheitert.
                // TODO bessere Methode um initiale SampleSize zu bestimmen, um Zeit zu sparen?
                // .... (eig. hinfällig mit optimiertem Algorithmus)
                while (sampleSize <= 32) {
                    try {
                        // Punktkoordinaten als float[8] abrufen
                        val coordinates = parent.get()!!.entzerrungsView!!.getPointOffsets(sampleSize)

                        // Bitmap erzeugen
                        val sampledBitmap = parent.get()!!.entzerrungsView!!.getSampledBitmap(sampleSize)
                        if (sampledBitmap == null) {
                            Log.e("EntzerrenTask/doInBackground", "Decoding bitmap with SampleSize $sampleSize resulted in null...")
                            sampleSize *= 2
                            continue
                        }

                        // Bitmap entzerren
                        entzerrtesBitmap = JumbledImage.transform(sampledBitmap, coordinates)
                        break
                    } catch (e: OutOfMemoryError) {
                        // Noch mal mit doppelter SampleSize (halbiere bisherige Bildgröße) versuchen
                        sampleSize *= 2
                        if (sampleSize > 32) {
                            // Exception weiterreichen
                            throw e
                        }
                    }
                }
                if (entzerrtesBitmap == null) {
                    Log.e("EntzerrenTask/doInBackground", "Couldn't decode stream after $sampleSize tries!")
                } else {
                    // entzerrtes Bild abspeichern
                    parent.get()!!.saveBitmap(entzerrtesBitmap!!, fileName)
                }
            } catch (e: OutOfMemoryError) {
                result = parent.get()!!.resources.getString(R.string.error_outofmemory)
                e.printStackTrace()
            } catch (e: ArrayIndexOutOfBoundsException) {
                result = parent.get()!!.resources.getString(R.string.deskewing_error_invalidcorners)
            } catch (e: IllegalArgumentException) {
                result = parent.get()!!.resources.getString(R.string.deskewing_error_invalidcorners)
            } catch (e: NullPointerException) {
                // passiert z.B. bei unpassendem Dateiformat (GIF?)
                // TODO irgendwas weiter machen? Entzerrung für GIFs von vornherein deaktivieren?
                result = parent.get()!!.resources.getString(R.string.deskewing_error_deskewfailure)
                Log.e("EntzerrenTask/doInBackground", "NullPointerException while trying to deskew image")
                e.printStackTrace()
            }
            return result
        }

        override fun onPostExecute(result: String?) {
            // execution of result of long time consuming operation
            if (result != null) {
                parent.get()?.showErrorMessage(result)
            } else {
                // entzerrtes Bild in die View laden
                parent.get()?.loadImageFile()
                parent.get()?.entzerrungsView!!.showCorners(false)
                parent.get()?.entzerrungsView!!.calcCornerDefaults()
                parent.get()?.entzerrt = true
            }
            parent.get()?.endLoadingScreen()
            parent.get()?.unlockScreenOrientation()
            parent.get()?.entzerrungsView!!.update()
        }
    }

    companion object {
        // savedInstanceState constants
        private const val SHOWHELPSAVED = "SHOWHELPSAVED"
        private const val IMAGEENTZERRT = "IMAGEENTZERRT"
    }
}
