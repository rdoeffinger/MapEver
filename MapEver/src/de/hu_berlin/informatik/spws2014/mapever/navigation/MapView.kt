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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import de.hu_berlin.informatik.spws2014.ImagePositionLocator.Point2D
import de.hu_berlin.informatik.spws2014.mapever.MapEverApp.Companion.getAbsoluteFilePath
import de.hu_berlin.informatik.spws2014.mapever.R
import de.hu_berlin.informatik.spws2014.mapever.largeimageview.LargeImageView
import java.io.FileNotFoundException
import java.util.*

class MapView : LargeImageView {
    // ////// NAVIGATION ACTIVITY CONTEXT
    var navigation: Navigation? = null

    // ////// MAP VIEW UND DATEN
    // Wurde das Bild bereits geladen?
    private var isMapLoaded = false

    // ////// MARKER FÜR DIE USERPOSITION
    // Marker für die Position des Users
    private var locationIcon: LocationIcon? = null
    var longClickPos: Point2D? = null

    // ////// BEHANDLUNG VON REFERENZPUNKTEN
    // Liste der gesetzten Referenzpunkte
    private val refPointIcons = HashSet<ReferencePointIcon>()

    // neu erstellter, aber unbestätigter Referenzpunkt
    private var unacceptedRefPointIcon: ReferencePointIcon? = null

    // Referenzpunkt, der zum Löschen ausgewählt wurde
    private var toDeleteRefPointIcon: ReferencePointIcon? = null

    // ////////////////////////////////////////////////////////////////////////
    // //////////// CONSTRUCTORS AND INITIALIZATION
    // ////////////////////////////////////////////////////////////////////////
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun onSaveInstanceState(): Parcelable {
        // LargeImageView gibt uns ein Bundle, in dem z.B. die Pan-Daten stecken. Verwende dieses als Basis.
        val bundle = super.onSaveInstanceState() as Bundle?

        // Falls ein neuer Referenzpunkt erstellt werden sollte, speichere seine Position (als Point2D).
        // (Timestamp nicht, der ist sowieso 0, solange der Punkt noch nicht akzeptiert wurde.)
        bundle!!.putSerializable(SAVEDUNACCEPTEDPOS,
                if (unacceptedRefPointIcon != null) unacceptedRefPointIcon!!.position else null)

        // Falls ein Referenzpunkt gelöscht werden sollte, speichere seine Position (als Point2D).
        bundle.putSerializable(SAVEDTODELETEPOS,
                if (toDeleteRefPointIcon != null) toDeleteRefPointIcon!!.position else null)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val bundle = state as Bundle

        // Falls ein neuer Referenzpunkt erstellt werden sollte, stelle diesen wieder her.
        val unacceptedPos = bundle.getSerializable(SAVEDUNACCEPTEDPOS) as Point2D?
        if (unacceptedPos != null) {
            // Referenzpunkt erstellen
            unacceptedRefPointIcon = null
            createUnacceptedReferencePoint(unacceptedPos)
        }

        // Falls ein Referenzpunkt gelöscht werden sollte, stelle die Auswahl wieder her.
        val deleteCandidatePos = bundle.getSerializable(SAVEDTODELETEPOS) as Point2D?
        if (deleteCandidatePos != null) {
            // Finde den zu löschenden Referenzpunkt
            for (refPoint in refPointIcons) {
                if (refPoint.position.equals(deleteCandidatePos)) {
                    // dieser Punkt war der Löschkandidat
                    registerAsDeletionCandidate(refPoint)
                    break
                }
            }
            if (toDeleteRefPointIcon == null) {
                Log.w("MapView/onRestoreInstanceState", "Tried to restore delete candidate but didn't find it: $deleteCandidatePos")
            }
        }

        // Im Bundle stecken noch Informationen von LargeImageView, z.B. Pan-Daten. Reiche das Bundle also weiter.
        super.onRestoreInstanceState(bundle)

        // Darstellung aktualisieren
        update()
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// USER INPUT HANDLING
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Behandlung von Touchevents.
     */
    // Lint-Warnung "MapView overrides onTouchEvent but not performClick", obwohl sich onTouchEvent[_clickDetection]()
    // um Aufruf von performClick() kümmern.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Dadurch, dass wir Klicks separat behandeln, können wir hier problemlos in den meisten Zuständen
        // das Panning und Zooming ausführen. Betrachte also nur Fälle, in denen wir KEIN Panning und Zooming
        // (oder zusätzlich etwas anderes) wollen.

        // Sind wir in einem Hilfezustand?
        if (navigation!!.state.isHelpState) {
            // Hier kein Pan/Zoom, aber Klicks sollen die Hilfe beenden.
            onTouchEvent_clickDetection(event)
            return true
        }

        // Führe Defaulthandler aus (der Klicks, Pan und Zoom behandelt)
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        val imagePos = screenToImagePosition(touchStartX, touchStartY)
        longClickPos = Point2D(imagePos.x.toInt(), imagePos.y.toInt())
        return super.performLongClick()
    }

    public override fun onClickPosition(clickX: Float, clickY: Float): Boolean {
        // Fallunterscheidung nach aktuellem Zustand
        val navState = navigation!!.state
        if (navState == NavigationStates.MARK_REFPOINT || navState == NavigationStates.ACCEPT_REFPOINT) {
            // ZUSTAND: Referenzpunkt setzen ODER akzeptieren.
            // Klicken bewirkt das Erstellen eines neuen unakzeptierten Referenzpunktes an der geklickten Position
            return onClickPosition_setRefPoint(clickX, clickY)
        } else if (navState == NavigationStates.DELETE_REFPOINT) {
            // ZUSTAND: Löschen eines ausgewählten Referenzpunktes
            // Klicken bricht Löschaktion ab.
            navigation!!.refPointDeleteBack(null)
            return true
        } else if (navState.isHelpState) {
            // ZUSTAND: Zustand X mit Schnellhilfe
            // Klicken bewirkt Ende der Schnellhilfe
            navigation!!.endQuickHelp()
            return true
        }

        // Kein Klickevent behandelt
        return false
    }

    /**
     * Behandlung des "Referenzpunkt setzen"-Modus:
     * Erstelle einen neuen (unakzeptierten) Referenzpunkt an der angeklickten Stelle.
     */
    private fun onClickPosition_setRefPoint(clickX: Float, clickY: Float): Boolean {
        // Bildposition berechnen, die angeklickt wurde
        val imagePos = screenToImagePosition(clickX, clickY)
        val xCoord = imagePos.x.toInt()
        val yCoord = imagePos.y.toInt()

        // Erstelle neuen unakzeptierten Referenzpunkt an dieser Stelle
        // (Sanitycheck der Koordinaten passiert dort)
        createUnacceptedReferencePoint(Point2D(xCoord, yCoord))

        // Falls unakzeptierter Referenzpunkt erfolgreich gesetzt wurde, wechsel Zustand
        if (unacceptedRefPointIcon != null) {
            // nächster Zustand: Bestätigen des gesetzten Referenzpunkts
            navigation!!.changeState(NavigationStates.ACCEPT_REFPOINT)
        }
        return true
    }

    override fun update() {
        // Super-Methode aufrufen (wichtig)
        super.update()
        if (isMapLoaded) {
            // Locationmarker aktualisieren
            updateLocationIcon()
        }
    }

    public override fun onTouchPanZoomChange() {
        // Panning/Zooming wurde durch Touch-Event verändert.

        // aufhören, den Standort zu fokusieren
        if (navigation!!.isPositionTracked) {
            navigation!!.stopTrackingPosition()
        }
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// DARSTELLUNG
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Aktualisiert die Position des LocationIcons.
     */
    fun updateLocationIcon() {
        if (locationIcon == null) return
        if (navigation!!.isUserPositionKnown) {
            // Wenn die aktuelle Benutzerposition bekannt ist, zeige Position an.
            // (Extra prüfen, ob es bereits sichtbar ist, bevor wir setVisibility aufrufen, ist etwas unnütz.)
            locationIcon!!.show()

            // Aktuelle Userposition abfragen
            val pos = navigation!!.userPosition!!

            // Limit how much outside the image we will show the marker
            // The user should still be able to scroll there!
            val limitX = imageWidth / 4
            val limitY = imageHeight / 4
            val newx = pos.x.coerceIn(-limitX, imageWidth + limitX)
            val newy = pos.y.coerceIn(-limitY, imageHeight + limitY)

            // Locationmarker updaten
            locationIcon!!.position = Point2D(newx, newy)
        } else {
            // Locationmarker verstecken, bis wieder neue Koordinaten bekannt sind
            locationIcon!!.hide()
        }
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// LADEN/VERWALTUNG VON BILD UND USERPOSITION
    // ////////////////////////////////////////////////////////////////////////
    /**
     * Lade Bild der Karte in die View.
     *
     * @param mapID ID des Karte, gleichzeitig der Dateiname des Bildes (oder 0 für Testkarte)
     * @throws FileNotFoundException
     */
    @Throws(FileNotFoundException::class)
    fun loadMap(mapID: Long) {
        // Karte laden, während bereits Karte geladen ist, macht keinen Sinn.
        if (isMapLoaded) return

        // Bild in die View laden mittels LargeImageView-Funktionalität
        if (mapID != 0L) {
            // absoluten Pfad der Bilddatei ermitteln
            val filename = getAbsoluteFilePath(mapID.toString())
            try {
                // Bild der LargeImageView auf diese Datei setzen
                setImageFilename(filename)
            } catch (e: FileNotFoundException) {
                Log.e("MapView/loadMap", "Konnte InputStream zu $mapID nicht öffnen!")
                e.printStackTrace()
                throw e
            }
        }
        if (mapID == 0L) {
            // Ohne Parameter oder im Fehlerfall wird Testkarte angezeigt
            setImageResourceRaw(TESTMAP_RESOURCE)
        }
        Log.d("MapView/loadMap", "Loading map #" + mapID + (if (mapID == 0L) " [test_karte]" else "")
                + " (image size " + imageWidth + "x" + imageHeight + ")")
        isMapLoaded = true

        // Erstelle LocationIcon und verstecke es zunächst.
        // (Erst anzeigen, sobald erste Koordinaten von der Lokalisierung eingetroffen sind.)
        locationIcon = LocationIcon(this)
        locationIcon!!.hide()

        // Darstellung aktualisieren
        update()
    }

    /**
     * Zentriert die Ansicht auf die aktuelle Benutzerposition.
     */
    fun centerCurrentLocation() {
        if (!navigation!!.isUserPositionKnown) return
        // Benutzerposition abfragen
        val location = navigation!!.userPosition

        // Pan-Zentrum auf die Position verschieben (ruft update() auf).
        setPanCenter(location!!.x.toFloat(), location.y.toFloat())
    }
    // ////////////////////////////////////////////////////////////////////////
    // //////////// REFERENZPUNKTE
    // ////////////////////////////////////////////////////////////////////////
    // ////// LADEN / VERWALTEN
    /**
     * Gibt die Anzahl aktuell gesetzter Referenzpunkte zurück.
     */
    fun countReferencePoints(): Int {
        return refPointIcons.size
    }

    /**
     * Erstellt ein ReferencePointIcon zu einem geladenen Referenzpunkt.
     *
     * @param pos Position des Referenzpunktes als Point2D
     * @param time Zeitstempel des Punktes
     */
    fun createLoadedReferencePoint(pos: Point2D, time: Long) {
        // Erstelle Referenzpunkt-Icon (repräsentiert zugleich den Referenzpunkt selbst)
        val newRefPointIcon = ReferencePointIcon(this, pos, time, true)

        // Füge Referenzpunkt in Liste ein
        refPointIcons.add(newRefPointIcon)

        // Wir registrieren den Punkt NICHT beim LDM, weil wir davon ausgehen, dass wir
        // ihn von dort bekommen haben...

        // Darstellung aktualisieren
        update()
    }
    // ////// ERSTELLEN / AKZEPTIEREN
    /**
     * Erstellt einen neuen unakzeptierten Referenzpunkt an der angegebenen Bildposition.
     *
     * @param pos Position des Referenzpunktes im Bild als Point2D
     */
    private fun createUnacceptedReferencePoint(pos: Point2D) {
        // Prüfe die Position auf Sinnhaftigkeit (vermeide Referenzpunkte außerhalb der Bildgrenzen)
        if (pos.x < 0 || pos.y < 0 || pos.x >= imageWidth || pos.y >= imageHeight) {
            // Fehlermeldung als Toast ausgeben
            Toast.makeText(context,
                    context.getString(R.string.navigation_toast_refpoint_out_of_boundaries),
                    Toast.LENGTH_SHORT).show()
            return
        }

        // Falls der Nutzer bereits vorher einen Referenzpunkt gesetzt und noch nicht bestätigt hat...
        if (unacceptedRefPointIcon != null) {
            // -> ... cancelt das Setzen eines neuen Referenzpunkts den alten Punkt.
            cancelReferencePoint()
        }

        // Erstelle Referenzpunkt-Icon (repräsentiert zugleich den Referenzpunkt selbst)
        // (Wir übergeben 0 als Zeit, weil der Timestamp erst beim Akzeptieren feststeht.)
        unacceptedRefPointIcon = ReferencePointIcon(this, pos, 0, false)

        // Darstellung aktualisieren
        update()
    }

    /**
     * Der noch unbestätigte Referenzpunkt unacceptedRefPointIcon wurde von der GUI bestätigt.
     */
    fun acceptReferencePoint() {
        // Nichts tun, falls kein unbestätigter Referenzpunkt vorhanden (sollte nicht passieren)
        if (unacceptedRefPointIcon == null) return

        // Aktuellen Timestamp setzen, weil die GPS-Koordinaten jünger als der unakzeptierte Punkt sein könnten.
        unacceptedRefPointIcon!!.timestamp = SystemClock.elapsedRealtime()

        // Referenzpunkt bei Lokalisierung eintragen
        val registerSuccessful = navigation!!.registerReferencePoint(unacceptedRefPointIcon!!.position,
                unacceptedRefPointIcon!!.timestamp)
        if (registerSuccessful) {
            // Füge Referenzpunkt in Liste ein
            refPointIcons.add(unacceptedRefPointIcon!!)

            // Beginne das Fading des RefPunkts
            unacceptedRefPointIcon!!.fadeOut()
        } else {
            // Referenzpunkt konnte nicht registriert werden -> ungültige/unsinnige Koordinaten?
            // Fehlermeldung anzeigen und Referenzpunkt löschen.
            Log.w("MapView/acceptReferencePoint", "addMarker for point " + unacceptedRefPointIcon!!.position
                    + " at time " + unacceptedRefPointIcon!!.timestamp + " returned false")
            Toast.makeText(context, context.getString(R.string.navigation_toast_refpoint_already_set_for_this_position), Toast.LENGTH_SHORT).show()

            // unakzeptierten Punkt verwerfen
            cancelReferencePoint()
        }

        // Referenz auf den Referenzpunkt freigeben
        unacceptedRefPointIcon = null
    }

    /**
     * Der noch unbestätigte Referenzpunkt soll verworfen werden.
     */
    fun cancelReferencePoint() {
        // Nichts tun, falls kein unbestätigter Referenzpunkt vorhanden (sollte nicht passieren)
        if (unacceptedRefPointIcon == null) return

        // unakzeptierten Referenzpunkt aus der Anzeige löschen
        unacceptedRefPointIcon!!.detach()

        // Referenz auf den Referenzpunkt freigeben (Objekt wird dem überlassen)
        unacceptedRefPointIcon = null
    }
    // ////// LÖSCHEN
    /**
     * Wird aufgerufen, wenn ein Referenzpunkt angeklickt wird, und setzt (wenn im richtigen Zustand) diesen Punkt
     * als Kandidat zum Löschen.
     */
    fun registerAsDeletionCandidate(deletionCandidate: ReferencePointIcon?): Boolean {
        // Prüfe, ob wir im RUNNING-Zustand sind oder bereits einen anderen Löschkandidaten ausgewählt haben.
        if (navigation!!.state != NavigationStates.RUNNING && navigation!!.state != NavigationStates.DELETE_REFPOINT) {
            // Wenn nicht, tu nichts! Die aufrufende onClick-Funktion soll das Event als nicht behandelt weitergeben.
            return false
        }

        // der Zustand ist nun "Referenzpunkt löschen"
        navigation!!.changeState(NavigationStates.DELETE_REFPOINT)

        // Wenn bereits einer zum Löschen ausgewählt ist, ändere die Auswahl und lass den alten wieder ausblenden
        if (toDeleteRefPointIcon != null) {
            toDeleteRefPointIcon!!.fadeOut()
        }

        // Setze diesen Referenzpunkt als Löschkandidaten
        toDeleteRefPointIcon = deletionCandidate

        // Blende Referenzpunkt zur Visualisierung ein.
        toDeleteRefPointIcon!!.fadeIn()

        // return true: Löschkandidat wurde ausgewählt (aufrufende onClick-Funktion gibt ebenfalls true zurück)
        return true
    }

    /**
     * Der ausgewählte Referenzpunkt soll gelöscht werden.
     */
    fun deleteReferencePoint() {
        // Nichts tun, falls kein Löschkandidat vorhanden (sollte nicht passieren)
        if (toDeleteRefPointIcon == null) return

        // Referenzpunkt aus der Lokalisierung löschen.
        navigation!!.unregisterReferencePoint(toDeleteRefPointIcon!!.position)

        // Entferne Referenzpunkt aus der Liste und von der Darstellung
        refPointIcons.remove(toDeleteRefPointIcon)
        toDeleteRefPointIcon!!.detach()

        // Referenz auf den Referenzpunkt freigeben (Objekt wird dem überlassen)
        toDeleteRefPointIcon = null
    }

    /**
     * Der ausgewählte Referenzpunkt soll nicht gelöscht werden.
     */
    fun dontDeleteReferencePoint() {
        // Nichts tun, falls kein Löschkandidat vorhanden (sollte nicht passieren)
        if (toDeleteRefPointIcon == null) return

        // Der Punkt wird wieder ausgeblendet und toDeleteRefPointView freigegeben
        toDeleteRefPointIcon!!.fadeOut()
        toDeleteRefPointIcon = null
    }

    companion object {
        // ////// KEYS FÜR ZU SPEICHERNDE DATEN IM SAVEDINSTANCESTATE-BUNDLE
        // Keys für unakzeptierten Referenzpunkt oder Löschkandidaten
        private const val SAVEDUNACCEPTEDPOS = "savedUnacceptedRefPointPosition"
        private const val SAVEDTODELETEPOS = "savedToDeleteRefPointPosition"

        // Konstante, die Resource ID der Testkarte angibt
        private const val TESTMAP_RESOURCE = R.raw.debug_testmap
    }
}
