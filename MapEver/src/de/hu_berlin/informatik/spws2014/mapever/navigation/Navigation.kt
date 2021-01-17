/* Copyright (C) 2014,2015  Björn Stelter, Hagen Sparka
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

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.hu_berlin.informatik.spws2014.ImagePositionLocator.*
import de.hu_berlin.informatik.spws2014.mapever.*
import de.hu_berlin.informatik.spws2014.mapever.MapEverApp.Companion.getAbsoluteFilePath
import de.hu_berlin.informatik.spws2014.mapever.MapEverApp.Companion.isDebugModeEnabled
import de.hu_berlin.informatik.spws2014.mapever.Thumbnail.generate
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import kotlin.math.max
import kotlin.math.min

class Navigation : BaseActivity(), LocationListener {
    /**
     * Gibt eine Referenz auf unser MapView zur�ck.
     */
    // ////// VIEWS
    // unsere Karte
    var mapView: MapView? = null
        private set

    // der Button zum Setzen des Referenzpunkts
    private var setRefPointButton: ImageButton? = null

    // der Button zum Setzen des Referenzpunkts (Akzeptieren)
    private var acceptRefPointButton: ImageButton? = null

    // der Button zum Setzen des Referenzpunkts (Abbrechen)
    private var cancelRefPointButton: ImageButton? = null

    // der Button zum L�schen eines Referenzpunkts
    private var deleteRefPointButton: ImageButton? = null

    // Button um Position zu verfolgen
    private var trackPositionButton: ImageButton? = null

    // Liste aller ImageButtons
    private val imageButtonList = ArrayList<ImageButton?>()

    // ////// KARTEN- UND NAVIGATIONSINFORMATIONEN
    // ID der aktuell geladenen Karte (zugleich Dateiname des Kartenbildes)
    private var currentMapID: Long = 0

    /**
     * Position des Users gemessen in Pixeln relativ zum Bild als Point2D.
     */
    // Position des Benutzers auf der Karte in Pixeln
    var userPosition: Point2D? = null
        private set
    private var intentPos: DoubleArray? = null

    // //////// POSITION TRACKING (AUTO CENTER)
    // Soll die aktuelle Position verfolgt (= zentriert) werden?
    var isPositionTracked = false
        private set

    // ////// LOKALISIERUNG
    // GPS-Lokalisierung
    private var locationManager: LocationManager? = null

    // Lokalisierungsalgorithmus
    private var locationDataManager: LocationDataManager? = null

    // Debug-GPS-Mocker
    private var mockStatusToast: Toast? = null
    private var mockBaseLocation: Location? = null

    // Umbenennen der Karte
    private var newMapName = ""

    // TODO bitte das hier drunter alles mal oben einsortieren
    // soll der Zustand gespeichert werden?
    private var saveState = true

    // der aktuelle Zustand der Navigation, der Anfangszustand ist RUNNING
    @JvmField
    var state = NavigationStates.RUNNING

    // "SuperLayout", in welches die anderen Layouts eingebunden werden, erm�glicht einfache Umsetzung von Overlays
    private var layoutFrame: FrameLayout? = null

    // XXX besser L�sen per Zustands�bergang
    private var quickTutorial = false

    // unser Men�
    private var menu: Menu? = null

    // die Anbindung an die Datenbank
    private var iLDMIOHandler: ILDMIOHandler? = null
    private var thisMap: TrackDBEntry? = null

    // ////////////////////////////////////////////////////////////////////////
    // //////////// ACTIVITY LIFECYCLE UND INITIALISIERUNG
    // ////////////////////////////////////////////////////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wenn die Activity in Folge einer Rotation neu erzeugt wird, gibt es einen gespeicherten Zustand in
        // savedInstanceState, sonst normal starten.
        Log.d("Navigation", "onCreate..." + if (savedInstanceState != null) " with savedInstanceState" else "")

        // ////// LAYOUT UND VIEWS

        // Layout aufbauen
        layoutFrame = FrameLayout(baseContext)
        setContentView(layoutFrame)
        layoutInflater.inflate(R.layout.activity_navigation, layoutFrame)

        // Initialisieren der ben�tigten Komponenten
        mapView = findViewById<View>(R.id.map) as MapView
        mapView!!.isFocusable = true
        mapView!!.isFocusableInTouchMode = true
        mapView!!.navigation = this

        // Initialisieren der Buttons & Eintragen in die Liste
        setRefPointButton = findViewById<View>(R.id.set_refpoint) as ImageButton
        imageButtonList.add(setRefPointButton)
        acceptRefPointButton = findViewById<View>(R.id.accept_refpoint) as ImageButton
        imageButtonList.add(acceptRefPointButton)
        cancelRefPointButton = findViewById<View>(R.id.cancel_refpoint) as ImageButton
        imageButtonList.add(cancelRefPointButton)
        deleteRefPointButton = findViewById<View>(R.id.delete_refpoint) as ImageButton
        imageButtonList.add(deleteRefPointButton)
        trackPositionButton = findViewById<View>(R.id.track_position) as ImageButton
        // Nicht den trackPositionButton in der Liste speichern, da dieser unabh�ngig vom Zustand gezeigt werden soll
        // imageButtonList.add(trackPositionButton);

        // Im Zweifelsfall kennen wir den Kartennamen nicht (sonst wird der aus der Datenbank geladen)
        title = getString(R.string.navigation_const_name_of_unnamed_maps)

        // ////// PARAMETER ERMITTELN UND ZUSTANDSVARIABLEN INITIALISIEREN (GGF. AUS STATE LADEN)
        intentPos = null
        if (savedInstanceState == null) {
            // -- Frischer Start --
            val intent = intent

            // ID der Karte (= Dateiname des Bildes)
            currentMapID = intent.extras!!.getLong(INTENT_LOADMAPID)

            // Initialisiere Startzustand der Navigation
            changeState(NavigationStates.RUNNING)
            saveState = true

            // aktuelle Position des Nutzers auf der Karte, null: noch nicht bekannt
            userPosition = null
            intentPos = intent.getDoubleArrayExtra(INTENT_POS)
        } else {
            // -- Gespeicherter Zustand --

            // ID der Karte (= Dateiname des Bildes)
            currentMapID = savedInstanceState.getLong(SAVEDCURRENTMAPID)

            // aktuelle Position des Nutzers auf der Karte
            userPosition = savedInstanceState.getSerializable(SAVEDUSERPOS) as Point2D?

            // ist das Kurztutorial aktiviert?
            quickTutorial = savedInstanceState.getBoolean(SAVEDHELPSTATE)

            // ist die Positionszentrierung aktiviert?
            isPositionTracked = savedInstanceState.getBoolean(SAVEDTRACKPOSITION)

            // Wiederherstellung des Zustands
            val restoredState = NavigationStates.values()[savedInstanceState.getInt(SAVEDSTATE)]
            if (restoredState.isHelpState) {
                // Sonderbehandlung beim Drehen im Bildschirm der Schnellhilfe
                // XXX besser machen
                state = restoredState
                layoutInflater.inflate(R.layout.navigation_help_running, layoutFrame)
                endQuickHelp()
                startQuickHelp()
            } else {
                changeState(restoredState)
            }
        }
        trackPositionButton!!.visibility = if (isPositionTracked) View.GONE else View.VISIBLE

        // ////// KARTE LADEN UND KOMPONENTEN INITIALISIEREN

        // Lade Karte und erstelle gegebenenfalls einen neuen Eintrag
        Log.d("onCreate", "Loading map: " + currentMapID + if (currentMapID == -1L) " (new map!)" else "")
        initLoadMap()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
        } else {
            // Initialisiere GPS-Modul
            initGPSModule()
        }

        // Initialiales update(), damit alles korrekt dargestellt wird
        mapView!!.update()
        if (intentPos != null) {
            val prev = locationDataManager!!.setSpeedFiltering(false)
            locationDataManager!!.addPoint(GpsPoint(intentPos!![1], intentPos!![0], SystemClock.elapsedRealtime()))
            locationDataManager!!.setSpeedFiltering(prev)

            // Change mode to set ref point
            changeState(NavigationStates.MARK_REFPOINT)
        }
        // Aktuelle Position zentrieren, falls tracking aktiviert
        if (isPositionTracked) {
            trackPosition(trackPositionButton)
        }
        registerForContextMenu(mapView)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Navigation", "onDestroy...")

        // Stelle NOCH MAL sicher, dass LDM-IO-Handler letzte Daten geschrieben hat.
        // Passiert eigentlich schon in onResume(), aber um auf Nummer sicher zu gehen...
        // TODO hier vielleicht mit resetIOHandler(null)? @diedricm ?
        if (locationDataManager != null) {
            iLDMIOHandler!!.save()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("Navigation", "onStart...")

        //Prompt user to activate GPS
        if (locationManager != null && !locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val gpsPromptListener = DialogInterface.OnClickListener { dialog, which ->
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                }
                dialog.dismiss()
            }
            val alertBuilder = AlertDialog.Builder(this)
            alertBuilder.setMessage(R.string.navigation_gps_activation_popup_question)
                    .setPositiveButton(android.R.string.yes, gpsPromptListener)
                    .setNegativeButton(android.R.string.no, gpsPromptListener)
                    .show()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("Navigation", "onStop...")
    }

    override fun onResume() {
        super.onResume()
        Log.d("Navigation", "onResume...")
        if (locationManager != null) {
            // Abonniere GPS Updates
            // TODO Genauigkeit (Parameter 2, 3)? default aus Tutorial (400, 1)
            locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0.2.toFloat(), this)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("Navigation", "onPause...")
        if (locationManager != null) {
            // Deabonniere GPS Updates
            locationManager!!.removeUpdates(this)
        }

        // Stelle sicher, dass LDM-IO-Handler letzte Daten geschrieben hat
        if (locationDataManager != null) {
            Log.d("onPause", "Schreibe letzte LDM-IO-Daten...")
            iLDMIOHandler!!.save()
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        Log.d("Navigation", "onSaveInstanceState...")
        if (saveState) {
            // ////// SAVE THE CURRENT STATE

            // ID der Karte (= Dateiname des Bildes)
            savedInstanceState.putLong(SAVEDCURRENTMAPID, currentMapID)

            // aktuelle Position des Nutzers auf der Karte als Point2D
            savedInstanceState.putSerializable(SAVEDUSERPOS, userPosition)

            // Zustand der Navigation speichern
            savedInstanceState.putInt(SAVEDSTATE, state.ordinal)

            // Zustand der Hilfe speichern
            savedInstanceState.putBoolean(SAVEDHELPSTATE, quickTutorial)

            // Zustand der Hilfe speichern
            savedInstanceState.putBoolean(SAVEDTRACKPOSITION, isPositionTracked)

            // neu erstellt werden:
            // LocationManager
            // LocationDataManager
            // LocationDataManagerListener
            // LocationDataManagerListenerExpertenWohnungsvermittlungFachangestellter
        }

        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState)
    }

    private fun returnToStart() {
        // Wenn wir zur�ckgehen, muss der Zustand nicht gespeichert werden
        saveState = false

        // Startbildschirm aufrufen und Activity finishen
        val intent = Intent(applicationContext, Start::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        if (intentPos != null) {
            intentPos = null
            intent.putExtra(Start.INTENT_EXIT, true)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Beim Bet�tigen der Zur�cktaste gelangen wir wieder zum Startbildschirm.
     */
    override fun onBackPressed() {
        // Mit der Zur�ck-Taste kann das L�schen von Referenzpunkten abgebrochen werden
        if (state == NavigationStates.DELETE_REFPOINT) {
            refPointDeleteBack(null)
            return
        }

        // ... genauso wie das Setzen von Referenzpunkten
        if (state == NavigationStates.MARK_REFPOINT || state == NavigationStates.ACCEPT_REFPOINT) {
            cancelReferencePoint()
            return
        }

        // ... und die Schnellhilfe
        if (state.isHelpState) {
            endQuickHelp()
            return
        }
        returnToStart()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.navigation, menu)
        this.menu = menu

        // Aktiviere Debug-Optionen, falls Debugmode aktiviert
        if (isDebugModeEnabled(this)) {
            menu.findItem(R.id.action_debugmode_mockgps).isVisible = true
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle item selection
        return when (item.itemId) {
            R.id.home -> {
                returnToStart()
                true
            }
            R.id.action_rename_map -> {
                changeState(NavigationStates.RENAME_MAP)
                true
            }
            R.id.action_rotate_map -> {
                val deg = (thisMap!!.rotation + 270) % 360
                mapView!!.setMapRotation(deg)
                thisMap!!.rotation = deg
                stopTrackingPosition()
                true
            }
            R.id.action_quick_help -> {
                // Schnellhilfe-Button
                startQuickHelp()
                true
            }
            R.id.action_debugmode_mockgps -> {
                // DEBUGMODE: Mock GPS coordinates
                debug_mockGPS()
                super.onOptionsItemSelected(item)
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val share = menu.add("Share this position")
        share.setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener {
            val pos = locationDataManager!!.getGpsPosition(mapView!!.longClickPos)
            if (pos == null) {
                if (!toastMissingRefpoints()) {
                    Toast.makeText(this@Navigation, "Unknown error translating position", Toast.LENGTH_SHORT).show()
                }
                return@OnMenuItemClickListener false
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("geo:" + pos.latitude + "," + pos.longitude)
            startActivity(intent)
            true
        })
    }

    private fun initLoadMap() {
        // ////// LDM-IO-HANDLER INITIALISIEREN
        Log.i("Nav", "currentMapID:$currentMapID")
        if (currentMapID == LOAD_TEST_MAP.toLong()) {
            // Spezialfall 0: Lade Testkarte
            // => Lade nichts aus der Datenbank, sondern benutze nichtpersistenten LDM. (Debugging)
            iLDMIOHandler = LDMIOEmpty()
        } else {
            if (!TrackDB.loadDB(File(getAbsoluteFilePath("")))) {
                Log.e("Nav", "Could not load DB")
                finish()
                return
            }

            // Haben wir eine neue Karte erstellt?
            if (currentMapID != CREATE_NEW_MAP.toLong()) {
                // Lade Karte mit der gegebenen ID aus der Datenbank
                // Falls ID = -1, wird ein neuer Eintrag f�r eine neue Karte erstellt (ID per auto increment)
                // pr�fen, ob Karte mit der ID existiert, sonst Fehlermeldung
                thisMap = TrackDB.main.getMap(currentMapID)
                currentMapID = thisMap!!.identifier
            } else {
                // ID der neuen Karte anfragen und merken
                thisMap = TrackDB.main.createMap()
                currentMapID = thisMap!!.identifier
                Log.d("Navigation/initLoadMap", "Neu erstellte Karte mit ID: " + thisMap!!.identifier)
                val targetFilename = getAbsoluteFilePath(thisMap!!.identifier.toString())
                val targetFilenameThumb = targetFilename + MapEverApp.THUMB_EXT

                // Bilddatei umbenennen
                val renameFrom = File(cacheDir.toString() + "/" + MapEverApp.TEMP_IMAGE_FILENAME)
                val renameTo = File(targetFilename)
                try {
                    renameFrom.copyTo(renameTo)
                } catch (e: IOException) {
                    // TODO: do something useful
                }

                // Thumbnail erstellen
                try {
                    val thumbSize: Int = Start.thumbnailSize
                    generate(targetFilename, targetFilenameThumb, thumbSize, thumbSize)
                } catch (e: IOException) {
                    Log.e("Navigation/initLoadMap", "Failed generating thumbnail for image '$targetFilename'!")
                    e.printStackTrace()
                }
            }
            iLDMIOHandler = try {
                TrackDB.main.getLDMIO(thisMap)
            } catch (e: IOException) {
                // ResourceID der Fehlermeldung an den Startbildschirm geben, dieser zeigt Fehlermeldung an
                setResult(R.string.navigation_map_not_found)
                finish()
                return
            }

            // wenn ein sinnvoller name vorhanden ist -> diesen anzeigen, sonst default Wert
            if (thisMap!!.mapname.isEmpty()) setTitle(R.string.navigation_const_name_of_unnamed_maps) else title = thisMap!!.mapname
        }

        // ////// BILD IN DIE MAPVIEW LADEN

        // (bei currentMapID == 0 wird die Testkarte geladen)
        try {
            mapView!!.loadMap(currentMapID)
        } catch (e: FileNotFoundException) {
            Log.e("Navigation/initLoadMap", "Konnte Karte $currentMapID nicht laden!")

            // ResourceID der Fehlermeldung an den Startbildschirm geben, dieser zeigt Fehlermeldung an
            setResult(R.string.navigation_image_not_found)
            finish()
            return
        }

        // Bilddimensionen m�ssen erkannt worden sein
        if (mapView!!.imageWidth == 0 || mapView!!.imageHeight == 0) {
            Log.e("Navigation/initLoadMap", "Bilddimensionen sind " + mapView!!.imageWidth + "x" + mapView!!.imageHeight)

            // (Sollte eigentlich eh nie vorkommen, also gib "Error" aus...)
            setResult(R.string.general_error_title)
            finish()
            return
        }
        mapView!!.setMapRotation(thisMap!!.rotation)

        // ////// LOCATIONDATAMANAGER INITIALISIEREN

        // Listener f�r neue Userkoordinaten erstellen
        val locDatManListener = LocationDataManagerListener(this)

        // LocationDataManager initialisieren
        val imageSize = Point2D(mapView!!.imageWidth, mapView!!.imageHeight)
        val locator: ImagePositionLocator
        locator = LeastSquaresImagePositionLocator()
        locationDataManager = LocationDataManager(locDatManListener, iLDMIOHandler,
                imageSize,
                locator)
        locationDataManager!!.refreshLastPosition()

        // ////// GELADENE REFERENZPUNKTE DARSTELLEN
        val loadedMarkers = iLDMIOHandler!!.allMarkers

        // TODO Check for corrupted maps! (LDM returns lots of nulls)
        if (loadedMarkers != null) {
            Log.d("Navigation/initLoadMap", "Lade " + loadedMarkers.size + " Referenzpunkte...")
            var minlat = 1000.0
            var minlon = 1000.0
            var maxlat = -1000.0
            var maxlon = -1000.0

            // Erstelle alle geladenen Referenzpunkte
            for (marker in loadedMarkers) {
                mapView!!.createLoadedReferencePoint(marker.imgpoint, marker.time)
                minlat = min(minlat, marker.realpoint.latitude)
                minlon = min(minlon, marker.realpoint.longitude)
                maxlat = max(maxlat, marker.realpoint.latitude)
                maxlon = max(maxlon, marker.realpoint.longitude)
            }
            thisMap!!.setMinMaxLatLon(minlat, minlon, maxlat, maxlon)
        }
    }

    // //////// GPS-MODUL INITIALISIEREN
    private fun initGPSModule() {

        // Initialisiere GPS-Modul
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0.2.toFloat(), this)
        Log.d("initGPSModule", "Location provider: " + LocationManager.GPS_PROVIDER)

        // Wir machen nichts mit der lastKnownLocation, siehe #169
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationManager == null &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            initGPSModule()
        }
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// HANDLING VON USERINPUT
    // ////////////////////////////////////////////////////////////////////////
    // //////// HILFE
    /**
     * Startet die Schnellhilfe, wenn nicht bereits aktiv.
     */
    private fun startQuickHelp() {
        // Bis RUNNING wieder erreicht ist, wird der Hilfebildschirm immer angezeigt
        quickTutorial = true
        when (state) {
            NavigationStates.ACCEPT_REFPOINT -> {
                layoutInflater.inflate(R.layout.navigation_help_accept_refpoint, layoutFrame)
                changeState(NavigationStates.HELP_ACCEPT_REFPOINT)
            }
            NavigationStates.DELETE_REFPOINT -> {
                layoutInflater.inflate(R.layout.navigation_help_delete_refpoint, layoutFrame)
                changeState(NavigationStates.HELP_DELETE_REFPOINT)
            }
            NavigationStates.MARK_REFPOINT -> {
                layoutInflater.inflate(R.layout.navigation_help_mark_refpoint, layoutFrame)
                changeState(NavigationStates.HELP_MARK_REFPOINT)
            }
            NavigationStates.RUNNING -> {
                layoutInflater.inflate(R.layout.navigation_help_running, layoutFrame)
                changeState(NavigationStates.HELP_RUNNING)
            }
            NavigationStates.HELP_ACCEPT_REFPOINT, NavigationStates.HELP_DELETE_REFPOINT, NavigationStates.HELP_MARK_REFPOINT, NavigationStates.HELP_RUNNING -> endQuickHelp()
            else -> Log.e("startQuickHelp", "Schnellhilfe f�r diesen Zustand fehlt noch!")
        }
    }

    /**
     * Beendet die Schnellhilfe und stellt den vorherigen Zustand wieder her.
     */
    fun endQuickHelp() {
        // alle Layer bis auf den untersten aus dem FrameLayout entfernen
        if (layoutFrame!!.childCount > 1) {
            layoutFrame!!.removeViews(layoutFrame!!.childCount - 1, layoutFrame!!.childCount - 1)
        }
        when (state) {
            NavigationStates.HELP_ACCEPT_REFPOINT -> changeState(NavigationStates.ACCEPT_REFPOINT)
            NavigationStates.HELP_DELETE_REFPOINT -> changeState(NavigationStates.DELETE_REFPOINT)
            NavigationStates.HELP_MARK_REFPOINT -> changeState(NavigationStates.MARK_REFPOINT)
            NavigationStates.HELP_RUNNING -> changeState(NavigationStates.RUNNING)
            else -> Log.e("endQuickHelp", "Diesen Text sollte man nie angezeigt bekommen! Zustand: $state")
        }
    }
    // //////// BUTTONS
    /**
     * Der User hat auf den "Referenzpunkt setzen" Button gedr�ckt.
     *
     * @param view
     */
    @Suppress("UNUSED_PARAMETER")
    fun setRefPoint(view: View?) {
        if (state != NavigationStates.RUNNING) {
            Log.w("setRefPoint", "Inkonsistenter Zustand: state != RUNNING")
        }

        // Pr�fe, ob wir momentan einen Referenzpunkt setzen d�rfen (sind bereits GPS-Koordinaten bekannt?)
        if (!locationDataManager!!.isMarkerPlacingAllowed) {
            // Zeige Nachricht an, dass auf GPS Ortung gewartet werden muss
            Toast.makeText(this, getString(R.string.navigation_toast_no_gpsfix_yet), Toast.LENGTH_SHORT).show()
            return
        }

        // Zustands�nderung zum "Referenzpunkt Setzen" Zustand
        changeState(NavigationStates.MARK_REFPOINT)

        // der bet�tigte Button wird verborgen
        setRefPointButton!!.visibility = View.INVISIBLE
        setRefPointButton!!.isEnabled = false
    }

    /**
     * Der Nutzer will den neu gesetzten Referenzpunkt behalten.
     *
     * @param view
     */
    @Suppress("UNUSED_PARAMETER")
    fun acceptReferencePoint(view: View?) {
        mapView!!.acceptReferencePoint()

        // Anschlie�end befinden wir uns wieder im Anfangszustand
        changeState(NavigationStates.RUNNING)
    }

    /**
     * Der Nutzer will den neu gesetzten Referenzpunkt verwerfen.
     *
     */
    private fun cancelReferencePoint() {
        // kann damit auch von onBackPressed aufgerufen werden, wenn wir noch in MARK_REFPOINT sind
        if (state == NavigationStates.ACCEPT_REFPOINT) {
            mapView!!.cancelReferencePoint()
        }

        // Anschlie�end befinden wir uns wieder im Anfangszustand
        changeState(NavigationStates.RUNNING)
    }

    @Suppress("UNUSED_PARAMETER")
    fun cancelReferencePoint(v: View?) {
        cancelReferencePoint()
    }

    /**
     * Der Button zum L�schen des Referenzpunkts wurde bet�tigt.
     *
     * @param view
     */
    @Suppress("UNUSED_PARAMETER")
    fun deleteReferencePoint(view: View?) {
        mapView!!.deleteReferencePoint()

        // Anschlie�end befinden wir uns wieder im Anfangszustand
        changeState(NavigationStates.RUNNING)
    }

    /**
     * Der ausgew�hlte Referenzpunkt soll doch nicht gel�scht werden.
     *
     * @param view
     */
    @Suppress("UNUSED_PARAMETER")
    fun refPointDeleteBack(view: View?) {
        mapView!!.dontDeleteReferencePoint()

        // Anschlie�end befinden wir uns wieder im Anfangszustand
        changeState(NavigationStates.RUNNING)
    }

    /**
     * Aktiviere Zentrierung des Locationmarkers.
     *
     * Needs to be public because it is referred to from XML!!
     *
     * @param view
     */
    fun trackPosition(view: View?) {
        if (!isUserPositionKnown) {
            if (!toastMissingRefpoints()) {
                Toast.makeText(this, getString(R.string.navigation_toast_no_gpsfix_yet), Toast.LENGTH_SHORT).show()
            }
            return
        }
        isPositionTracked = true
        view!!.visibility = View.GONE
        mapView!!.centerCurrentLocation()
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// HILFSFUNKTIONEN
    // ////////////////////////////////////////////////////////////////////////
    // //////// ZUSTANDS�NDERUNGEN
    /**
     * Simpler Zustands�bergang ohne gro�e Rafinesse.
     *
     * @param nextState
     */
    fun changeState(nextState: NavigationStates) {
        var changeToHelp = false
        var changeFromHelp = false
        val oldState = state
        state = nextState
        Log.d("changeState", "Zustands�bergang von $oldState zu $state")
        if (oldState.isHelpState) {
            changeFromHelp = true
        } else if (oldState == NavigationStates.RENAME_MAP) {

            // wenn ein neuer Name eingegeben wurde, so ist er in newMapName gespeichert
            // thisMap == null really only happens for the test map
            if (newMapName.isNotEmpty() && thisMap != null) {
                title = newMapName
                supportActionBar!!.title = newMapName
                thisMap!!.mapname = newMapName
                newMapName = ""
            }
        }

        // erst mal alle Buttons deaktivieren
        for (imageButton in imageButtonList) {
            imageButton!!.isEnabled = false
            imageButton.visibility = View.INVISIBLE
        }

        // alle Layer bis auf den untersten aus dem FrameLayout entfernen
        // TODO nee, oder? siehe Bug #231
        // if (layoutFrame.getChildCount() > 1) {
        // layoutFrame.removeViews(layoutFrame.getChildCount() - 1, layoutFrame.getChildCount() - 1);
        // }

        // Rename Men�option deaktivieren
        if (menu != null) {
            menu!!.findItem(R.id.action_rename_map).isVisible = false
        }
        when (state) {
            NavigationStates.ACCEPT_REFPOINT -> {
                // Buttons zum Akzeptieren und Verwerfen des gesetzten Referenzpunkts m�ssen angezeigt werden
                acceptRefPointButton!!.visibility = View.VISIBLE
                acceptRefPointButton!!.isEnabled = true
                cancelRefPointButton!!.visibility = View.VISIBLE
                cancelRefPointButton!!.isEnabled = true
            }
            NavigationStates.DELETE_REFPOINT -> {
                // Button zum L�schen des Referenzpunkts muss angezeigt werden
                deleteRefPointButton!!.visibility = View.VISIBLE
                deleteRefPointButton!!.isEnabled = true
            }
            NavigationStates.HELP_ACCEPT_REFPOINT -> {
                // Buttons von Accept_Refpoint einblenden aber nicht aktivieren
                acceptRefPointButton!!.visibility = View.VISIBLE
                cancelRefPointButton!!.visibility = View.VISIBLE

                // Es handelt sich um einen Wechsel zur Schnellhilfe
                changeToHelp = true
            }
            NavigationStates.HELP_DELETE_REFPOINT -> {
                // Buttons von Delete_Refpoint einblenden aber nicht aktivieren
                deleteRefPointButton!!.visibility = View.VISIBLE
                changeToHelp = true
            }
            NavigationStates.HELP_MARK_REFPOINT ->             // hier gibt es aktuell keine Buttons anzuzeigen
                changeToHelp = true
            NavigationStates.HELP_RUNNING -> {
                // Buttons von RUNNING einblenden aber nicht aktivieren
                setRefPointButton!!.visibility = View.VISIBLE
                changeToHelp = true
            }
            NavigationStates.MARK_REFPOINT -> {
            }
            NavigationStates.RENAME_MAP -> {

                // TODO das wirkt irgendwie alles so umst�ndlich... xD

                // erstelle AlertDialog f�r die schickere Umbenennung
                val builder = AlertDialog.Builder(this)
                builder.setTitle(R.string.navigation_rename_map)

                // Dr�cken des Umbenennen-Buttons benennt die Karte um
                builder.setPositiveButton(R.string.navigation_rename_map_rename) { _, _ -> changeState(NavigationStates.RUNNING) }

                // Dr�cken des Cancel-Buttons beendet die Umbenennung
                builder.setNegativeButton(R.string.navigation_rename_map_cancel) { _, _ -> // der Kartenname soll nicht ge�ndert werden!
                    newMapName = ""
                    changeState(NavigationStates.RUNNING)
                }

                // TODO was ist hier die Alternative zu null?
                val renameDialogLayout = layoutInflater.inflate(R.layout.navigation_rename_map, null)

                // bindet das Layout navigation_rename_map ein
                builder.setView(renameDialogLayout)
                val dialog = builder.create()

                // Behandelt alle Arten den AlertDialog abzubrechen, ohne die Kn�pfe zu verwenden
                dialog.setOnCancelListener { // der Kartenname soll nicht ge�ndert werden!
                    newMapName = ""
                    changeState(NavigationStates.RUNNING)
                }

                // Anzeigen des Dialog
                dialog.show()
                val input = renameDialogLayout.findViewById<EditText>(R.id.editTextToNameMap)

                // Wenn der Text ge�ndert wird, wird der String newMapName angepasst
                input.addTextChangedListener(object : TextWatcher {
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                    override fun afterTextChanged(s: Editable) {
                        newMapName = s.toString()
                    }
                })

                // der Aktuelle Kartenname wird dem Benutzer angezeigt
                input.setText(title)
            }
            NavigationStates.RUNNING -> {
                // Button zum Setzen von Referenzpunkten einblenden
                setRefPointButton!!.visibility = View.VISIBLE
                setRefPointButton!!.isEnabled = true

                // Wenn wir keine aktuelle GPS Position haben, Button "ausgegraut" anzeigen und deaktivieren
                if (locationDataManager == null || !locationDataManager!!.isMarkerPlacingAllowed) {
                    disableSetRefPointButton(true)
                } else {
                    // ansonsten Button wieder aktivieren
                    disableSetRefPointButton(false)
                }
                trackPositionButton!!.visibility = if (isPositionTracked) View.GONE else View.VISIBLE

                // nur in RUNNING kann man die Karte umbenennen
                if (menu != null) {
                    menu!!.findItem(R.id.action_rename_map).isVisible = true
                }
                if (intentPos != null) {
                    returnToStart()
                    return
                }
            }
            else -> {
            }
        }

        // Das Kurztutorial soll nur angezeigt werden, wenn es
        // a) aktiviert ist
        // und b) wir nicht zu einem Hilfebildschirm wechseln oder von einem solchen kommen
        if (quickTutorial && !changeFromHelp && !changeToHelp) {
            // wenn wir wieder bei RUNNING angekommen sind, wird die Hilfe erst wieder auf Nutzerwunsch ausgel�st
            if (state == NavigationStates.RUNNING) {
                quickTutorial = false
            } else {
                // Ansonsten zeigen wir den Hilfe-Bildschirm an
                startQuickHelp()
            }
        }
    }

    /**
     * Deaktiviert den Referenzpunkt-Setzen-Button (d.h. er wird ausgegraut, bleibt aber enabled) oder aktiviert ihn.
     *
     * @param disable false zum Reaktivieren des Buttons
     */
    private fun disableSetRefPointButton(disable: Boolean) {
        if (disable) {
            // Button "ausgegraut" anzeigen
            setRefPointButton!!.setColorFilter(Color.GRAY)
            setRefPointButton!!.background.alpha = 127
        } else {
            // Button aktivieren und ColorFilter entfernen
            setRefPointButton!!.clearColorFilter()
            setRefPointButton!!.background.alpha = 255
        }
    }

    // ////////////////////////////////////////////////////////////////////////
    // //////////// LOKALISIERUNG
    // ////////////////////////////////////////////////////////////////////////
    // //////// GPS
    override fun onLocationChanged(location: Location?) {
        if (location == null || locationDataManager == null || intentPos != null) return

        // Wenn wir in RUNNING sind und wir bisher keine aktuellen Koordinaten hatten, m�ssen wir den (ausgegrauten)
        // Referenzpunkt-Setzen-Button reaktivieren.
        if (state == NavigationStates.RUNNING && !locationDataManager!!.isMarkerPlacingAllowed) {
            disableSetRefPointButton(false)
        }

        // DEBUG: GPS-Mocker: zuf�llige Koordinaten sollen um Startposition herum gestreut werden
        if (mockBaseLocation == null) {
            mockBaseLocation = location
        }

        // Ermittle Koordinatenkomponenten
        val lng = location.longitude
        val lat = location.latitude
        Log.d("Navigation", "GPS location changed: $lat� N / $lng� E")

        // �bergebe der Lokalisierung den aktuellen GPS-Punkt
        val gpsPoint = GpsPoint(lng, lat, SystemClock.elapsedRealtime())
        locationDataManager!!.addPoint(gpsPoint)

        // Der LocationDataManagerListener wird spaeter onNewUserPosition()
        // aufrufen, was das LocationView entsprechend verschiebt.
    }

    // TODO bin mir nicht sicher, ob hier was gemacht werden muss...
    override fun onProviderEnabled(provider: String) {
        Log.d("Navigation", "Provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.d("Navigation", "Provider disabled: $provider")
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
    fun stopTrackingPosition() {
        isPositionTracked = false
        trackPositionButton!!.visibility = View.VISIBLE
    }

    // //////// DEBUG MODE - GPS
    // Simuliert zuf�llige GPS-Koordinaten
    private fun debug_mockGPS() {
        // TODO eventuell GPS-Provider deaktivieren? mit der Methode, die in onPause verwendet wird
        val random = Random()
        val mockRadius = 0.1

        // mockBaseLocation ist der Punkt, der als Zentrum f�r die zuf�llige Verteilung gew�hlt wird (Startkoordinaten)
        if (mockBaseLocation == null) {
            // Verwende folgende Default-Koordinaten, wenn keine Startkoordinaten bekannt (das ist der Fernsehturm :) )
            mockBaseLocation = Location("mock")
            mockBaseLocation!!.latitude = 52.520818
            mockBaseLocation!!.longitude = 13.409403
        }
        val mockLoc = Location(mockBaseLocation)
        var dLat: Double
        var dLon: Double
        val toastText: String

        // Falls wir im Referenzpunkt-Setz-Modus sind, simuliere Hilfspunkte statt zuf�llige!
        // D.h. je nach Anzahl der Referenzpunkte, biete einen Punkt oben mittig, links unten oder rechts unten an.
        if (state == NavigationStates.MARK_REFPOINT || state == NavigationStates.ACCEPT_REFPOINT) {
            when (mapView!!.countReferencePoints()) {
                0 -> {
                    dLat = 0.5 * mockRadius
                    dLon = 0.0
                    toastText = "Mitte oben (.5, 0)"
                }
                1 -> {
                    dLat = -0.5 * mockRadius
                    dLon = -0.5 * mockRadius
                    toastText = "Links unten (-.5, -.5)"
                }
                2 -> {
                    dLat = -0.5 * mockRadius
                    dLon = 0.5 * mockRadius
                    toastText = "Rechts unten (-.5, .5)"
                }
                else -> {
                    run {
                        dLon = 0.0
                        dLat = dLon
                    }
                    toastText = "Mittelpunkt (0, 0)"
                }
            }
        } else {
            dLat = (random.nextDouble() - 0.5) * 2 * mockRadius
            dLon = (random.nextDouble() - 0.5) * 2 * mockRadius
            toastText = """
                dLat ${dLat / mockRadius}
                dLon ${dLon / mockRadius}
                (relative to mock radius)
                """.trimIndent()
        }
        mockLoc.latitude = mockLoc.latitude + dLat
        mockLoc.longitude = mockLoc.longitude + dLon

        // Koordinaten einspei�en
        Log.d("debug_mockGPS", "Set GPS coordinates to dLat = $dLat, dLon = $dLon (relative to starting point)")
        onLocationChanged(mockLoc)

        // Koordinatendifferenz in Toast anzeigen
        if (mockStatusToast != null) {
            mockStatusToast!!.cancel()
        }
        mockStatusToast = Toast.makeText(this, toastText, Toast.LENGTH_SHORT)
        mockStatusToast!!.show()
    }
    // //////// BILD-LOKALISIERUNG
    /**
     * Wird vom LocationDataManagerListener beim Erhalten neuer Userposition aufgerufen.
     */
    fun onNewUserPosition() {
        // Aktuellste Position vom LocationDataManager holen und setzen
        val userPosition = locationDataManager!!.lastImagePoint
        if (userPosition == null || userPosition.x == 0 && userPosition.y == 0) {
            // Sollte eigentlich nicht mehr auftreten... wenn doch, return.
            Log.w("onNewUserPosition", "getLastImagePoint() returned " + if (userPosition == null) "null" else "(0,0")
            return
        }
        Log.d("Navigation", "Image position changed: " + userPosition.x + "px / " + userPosition.y + "px")

        // Verschiebe den LocationView-Marker
        setUserPosition(userPosition)
    }

    /**
     * Position des Users gemessen in Pixeln relativ zum Bild setzen.
     * Aktualisiert die Darstellung der LocationView.
     *
     * @param newPos Koordinaten als Point2D
     */
    private fun setUserPosition(newPos: Point2D) {
        userPosition = newPos

        // Locationmarker aktualisieren
        mapView!!.updateLocationIcon()

        // Aktuelle Position zentrieren, falls tracking aktiviert
        if (isPositionTracked) {
            mapView!!.centerCurrentLocation()
        }
    }

    /**
     * Gibt true zur�ck, wenn die aktuelle Benutzerposition bekannt ist, sonst false.
     */
    val isUserPositionKnown: Boolean
        get() = userPosition != null

    private fun toastMissingRefpoints(): Boolean {
        // Wieviele Referenzpunkte muss der Nutzer noch setzen?
        val refPointsLeftToSet = locationDataManager!!.remainingUserMarkerInputs()
        if (refPointsLeftToSet <= 0) return false

        // Wenn der Nutzer noch nicht genug Referenzpunkte gesetzt hat, wird er darauf hingewiesen
        Toast.makeText(this,
                getString(R.string.navigation_toast_set_refpoint_prompt, refPointsLeftToSet),
                Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /**
     * Tr�gt einen neuen Referenzpunkt an der �bergebenen Position und mit dem
     * �bergebenen timestamp beim LocationDataManager ein
     *
     * @param position
     * @param timestamp
     */
    fun registerReferencePoint(position: Point2D, timestamp: Long): Boolean {
        Log.d("registerReferencePoint", "Position: $position, time: $timestamp")
        try {
            locationDataManager!!.addMarker(position, timestamp)
        } catch (e: NoGpsDataAvailableException) {
            // Keine (neuen) GPS-Daten sind verf�gbar -> f�r diese GPS-Koordinaten existiert bereits ein Referenzpunkt
            Log.w("registerReferencePoint", "addMarker failed because of NoGpsDataAvailableException: " + e.message)

            // Fehlermeldung per Toast anzeigen
            val errorMsgID = if (e.message == "Point already known!") R.string.navigation_toast_refpoint_already_set_for_this_position else R.string.navigation_toast_no_gpsfix_yet
            Toast.makeText(this, getString(errorMsgID), Toast.LENGTH_SHORT).show()

            // aufrufende Funktion muss z.B. den unakzeptierten Referenzpunkt aufr�umen
            return false
        } catch (e: PointNotInImageBoundsException) {
            // Gew�nschter Referenzpunkt befindet sich au�erhalb der Bildgrenzen! (nicht m�glich)
            Log.w("registerReferencePoint", "addMarker failed because of PointNotInImageBoundsException: " + e.message)

            // Fehlermeldung per Toast anzeigen
            Toast.makeText(this, getString(R.string.navigation_toast_refpoint_out_of_boundaries), Toast.LENGTH_SHORT).show()

            // aufrufende Funktion muss z.B. den unakzeptierten Referenzpunkt aufr�umen
            return false
        }
        toastMissingRefpoints()
        return true
    }

    /**
     * Weist den LocationDataManager an, einen Referenzpunkt zu l�schen.
     *
     * @param position
     */
    fun unregisterReferencePoint(position: Point2D): Boolean {
        Log.d("unregisterReferencePoint", "Position: $position")
        val result = iLDMIOHandler!!.removeMarker(position)
        locationDataManager!!.refreshLastPosition()
        return result
    }

    companion object {
        // ////// KEYS F�R ZU SPEICHERNDE DATEN IM SAVEDINSTANCESTATE-BUNDLE
        // Key f�r ID der geladenen Karte (long)
        private const val SAVEDCURRENTMAPID = "savedCurrentMapID"

        // Key f�r UserPosition (Point2D)
        private const val SAVEDUSERPOS = "savedUserPosition"

        // Key f�r den Zustand der Navigation (NavigationState)
        private const val SAVEDSTATE = "savedState"

        // Key f�r den Zustand der Hilfe (boolean)
        private const val SAVEDHELPSTATE = "savedHelpState"

        // Key f�r den Zustand der Positionszentrierungsfunktion (boolean)
        private const val SAVEDTRACKPOSITION = "savedTrackPosition"
        // ////// STATIC VARIABLES AND CONSTANTS
        /**
         * INTENT_LOADMAPID: ID der zu ladenden Map als long
         */
        const val INTENT_LOADMAPID = "de.hu_berlin.informatik.spws2014.mapever.navigation.Navigation.LoadMapID"
        const val INTENT_POS = "de.hu_berlin.informatik.spws2014.mapever.navigation.Navigation.IntentPos"

        // ////////////////////////////////////////////////////////////////////////
        // //////////// INITIALISIERUNG VON KARTE UND GPS-MODUL
        // ////////////////////////////////////////////////////////////////////////
        // //////// KARTE LADEN / NEUE KARTE ERSTELLEN
        private const val LOAD_TEST_MAP = 0
        private const val CREATE_NEW_MAP = -1
    }
}
