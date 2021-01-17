/* Copyright (C) 2014,2015 Björn Stelter
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
package de.hu_berlin.informatik.spws2014.mapever

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import de.hu_berlin.informatik.spws2014.mapever.Settings.Companion.getPreference_debugMode
import java.io.File
import java.io.IOException

// WAS IST DAS?
// "Application" sorgt für einen globalen (d.h. app-weiten) Kontext, in dem wir
// beispielsweise unser GPS-Objekt speichern können, dieses bereits in der
// Start-Activity initialisieren und dann später in Navigation verwenden können.
// Wir erzeugen also eine Erweiterung dieser Klasse um eigene Objekte zu speichern.
// Die Referenz auf diesen App-Kontext erhalten wir in einer Activity mittels:
// MapEverApp appContext = ((MapEverApp)getApplicationContext());
//
// SIEHE AUCH: http://stackoverflow.com/a/708317
class MapEverApp : Application() {
    companion object {
        // Basisverzeichnis, in dem unsere Dateien zu finden sind
        private const val BASE_DIR_DIRNAME = "mapever"
        private val BASE_DIR = Environment.getExternalStorageDirectory().absolutePath + File.separator + BASE_DIR_DIRNAME
        const val TEMP_IMAGE_FILENAME = "temp"
        const val THUMB_EXT = "_thumb"
        fun initializeBaseDir() {
            val baseDir = File(BASE_DIR)

            // Erstelle App-Verzeichnis, falls dieses noch nicht existiert.
            if (!baseDir.exists()) {
                Log.d("MapEverApp", "Base directory does not exist, creating new one at '$BASE_DIR'")
                if (!baseDir.mkdirs()) {
                    Log.e("MapEverApp", "Failed to initialize base directory, mkdirs returned false!")
                    return
                }
                val nomediaFile = File(BASE_DIR + File.separator + ".nomedia")
                try {
                    nomediaFile.createNewFile()
                } catch (e: IOException) {
                    Log.e("MapEverApp", "Failed to create .nomedia file")
                    e.printStackTrace()
                }
            }
        }

        /**
         * Wandelt einen relativen Pfad in einen absoluten Pfad um. Die Pfade sind dabei relativ zum Appverzeichnis
         * (externalStorageDirectory + "/mapever/") anzugeben.
         *
         * @param relativeFilename
         * @return
         */
        @JvmStatic
        fun getAbsoluteFilePath(relativeFilename: String): String {
            return BASE_DIR + File.separator + relativeFilename
        }

        /**
         * Gibt true zurück, falls Debug-Mode aktiviert ist.
         */
        @JvmStatic
        fun isDebugModeEnabled(context: Context?): Boolean {
            return getPreference_debugMode(context)
        }
    }

    init {
        // ////// INITIALIZE APP

        // Erstelle App-Verzeichnis, falls dieses noch nicht existiert.
        initializeBaseDir()
    }
}