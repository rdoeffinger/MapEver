/* Copyright (C) 2014,2015 Hendryk Köppel, Florian Kempf, Hauke Heinrichs
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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.widget.*
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import de.hu_berlin.informatik.spws2014.ImagePositionLocator.TrackDB
import de.hu_berlin.informatik.spws2014.ImagePositionLocator.TrackDBEntry
import de.hu_berlin.informatik.spws2014.mapever.entzerrung.Entzerren
import de.hu_berlin.informatik.spws2014.mapever.navigation.Navigation
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.regex.Pattern

class Start : BaseActivity() {
    private var layout: FrameLayout? = null

    // Neue Karte Popup
    private var newMapPopup: AlertDialog? = null

    // keeps track of the maps and their respective positions in the grid
    private val positionIdList = ArrayList<TrackDBEntry>()
    private val bitmapList = ArrayList<Bitmap?>()

    // track the state of some GUI-elements for orientation changes
    private var isPopupOpen = false
    private var isHelpShown = false
    private var noMaps = true
    private var intentPos: DoubleArray? = null
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (TrackDB.main == null &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            MapEverApp.initializeBaseDir()
            if (!TrackDB.loadDB(File(MapEverApp.getAbsoluteFilePath("")))) {
                trackDBErrorAlert()
                return
            }
            // hack since refreshMapGrid does not work
            val intent = intent
            finish()
            startActivity(intent)
        }
    }

    private fun trackDBErrorAlert() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("error")
        builder.setMessage("unable to read TrackDB")
        builder.setNeutralButton("Close", null)
        builder.create().show()
    }

    // TODO: cannot find a way to actually make it refresh the view
    private fun refreshMapGrid() {
        val mapList = if (TrackDB.main == null) ArrayList() else ArrayList(TrackDB.main.allMaps)
        Collections.sort(mapList, Comparator { t1, t2 ->
            if (t1.identifier > t2.identifier) return@Comparator -1
            if (t1.identifier < t2.identifier) 1 else 0
        })

        // Debug
        println("getAllEntries: $mapList")
        noMaps = mapList.isEmpty()
        val resources = resources
        bitmapList.clear()
        positionIdList.clear()

        // if maps are present, get them from the list and assign them to the positions in the grid
        if (!noMaps) {
            bitmapList.ensureCapacity(mapList.size)
            positionIdList.ensureCapacity(mapList.size)
            for (d in mapList) {
                positionIdList.add(d)

                // get the ID of the map
                val id_string = d.identifier.toString()
                val thumbFile = File(MapEverApp.getAbsoluteFilePath(id_string + "_thumb"))
                var thumbBitmap: Bitmap? = null

                // try to load bitmap of thumbnail if it exists
                if (thumbFile.exists()) {
                    thumbBitmap = BitmapFactory.decodeFile(thumbFile.absolutePath)
                }

                // add the thumbnail of the map to the bitmapList if it exists, a dummy picture otherwise
                if (thumbBitmap != null) {
                    bitmapList.add(thumbBitmap)
                } else {
                    bitmapList.add(BitmapFactory.decodeResource(resources, R.drawable.map_dummy))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("Start", "onCreate..." + if (savedInstanceState != null) " with savedInstanceState" else "")
        super.onCreate(savedInstanceState)

        // We do not want to have multiple instances, as that ends up with race
        // conditions on reading/writing the data files.
        // Using singleTask for this is simply broken.
        // So instead forward the intent to a "clean" task if necessary
        if (!isTaskRoot && intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK) == 0) {
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
            return
        }
        if (savedInstanceState == null) {
            val intent = intent
            if (intent != null && intent.getBooleanExtra(INTENT_EXIT, false)) {
                finish()
                return
            }
            if (intent != null && intent.data != null && intent.data!!.scheme != null && intent.data!!.scheme == "geo") {
                val pos = intent.data!!.schemeSpecificPart
                val p = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?).*")
                val m = p.matcher(pos)
                if (m.find()) {
                    intentPos = doubleArrayOf(m.group(1)!!.toDouble(), m.group(2)!!.toDouble())
                }
            }
        }
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false)
        layout = FrameLayout(baseContext)
        setContentView(layout)
        layoutInflater.inflate(R.layout.activity_start, layout)

        // number of columns for the gridview
        val column = 3

        // get a list of all maps from the databse
        if (!TrackDB.loadDB(File(MapEverApp.getAbsoluteFilePath("")))) {
            Log.e("Start", "Could not start DB!")
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 0)
            } else {
                trackDBErrorAlert()
            }
        }
        val gridview = findViewById<View>(R.id.start) as GridView
        gridview.numColumns = column
        gridview.adapter = ImageAdapter(this)
        refreshMapGrid()

        // ////////// hier wird das popup fenster erstellt ///////////////
        newMapPopup = AlertDialog.Builder(this@Start).create()
        // /////////sobald man irgendwo ausserhalb den bildschirm beruehrt
        // /////////wird das popup geschlossen
        newMapPopup!!.setCanceledOnTouchOutside(true)
        newMapPopup!!.setOnCancelListener { isPopupOpen = false }
        gridview.onItemClickListener = OnItemClickListener { _, _, position, _ -> // clicks auf die Karten ignorieren, wenn das Hilfe-Overlay angezeigt wird
            if (isHelpShown) {
                return@OnItemClickListener
            }

            // Toast.makeText(Start.this, "" + position, Toast.LENGTH_SHORT).show();
            val intent = Intent(applicationContext, Navigation::class.java)
            intent.putExtra(Navigation.INTENT_LOADMAPID, positionIdList[position].identifier)
            intent.putExtra(Navigation.INTENT_POS, intentPos)
            startActivityForResult(intent, NAVIGATION_REQUESTCODE)
        }
        registerForContextMenu(gridview)
    }

    internal inner class ImageAdapter(private val mContext: Context) : BaseAdapter() {
        private val displayMetrics = resources.displayMetrics
        override fun getCount(): Int {
            return bitmapList.size
        }

        override fun getItem(position: Int): Any? {
            return null
        }

        override fun getItemId(position: Int): Long {
            return 0
        }

        fun getPx(dimensionDp: Int): Int {
            val density = displayMetrics.density
            return (dimensionDp * density + 0.5f).toInt()
        }

        // handles, if tiles can be clicked
        override fun isEnabled(position: Int): Boolean {
            return !noMaps
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var imageView = ImageView(mContext)
            if (convertView == null) {
                // About 3/4 inch seems like a good size
                imageView.layoutParams = AbsListView.LayoutParams(thumbnailSize, thumbnailSize)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setPadding(getPx(8), getPx(8), getPx(8), getPx(8))
            } else {
                imageView = convertView as ImageView
            }

            // adds bitmaps of the map-thumbnails to the grid
            if (bitmapList[position] != null) {
                imageView.setImageBitmap(bitmapList[position])
            }
            return imageView
        }

        init {
            thumbnailSize = (120 * displayMetrics.density).toInt()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("Start", "onStart...")
    }

    override fun onStop() {
        super.onStop()
        Log.d("Start", "onStop...")
    }

    override fun onResume() {
        super.onResume()
        Log.d("Start", "onResume...")
        if (isPopupOpen) {
            showNewMapPopup()
        }
    }

    public override fun onPause() {
        super.onPause()
        Log.d("Start", "onPause...")
        if (newMapPopup != null) {
            newMapPopup!!.dismiss()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.start, menu)

        // If in debug mode, activate debug options
        if (MapEverApp.isDebugModeEnabled(this)) {
            menu.findItem(R.id.action_load_testmap).isVisible = true
        }
        return super.onCreateOptionsMenu(menu)
    }

    // application-state handling
    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBoolean("isPopupOpen", isPopupOpen)
        savedInstanceState.putBoolean("isHelpShown", isHelpShown)
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isPopupOpen = savedInstanceState.getBoolean("isPopupOpen")
        isHelpShown = savedInstanceState.getBoolean("isHelpShown")
        if (isHelpShown) {
            isHelpShown = false
            toggleQuickHelp()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == R.id.action_quick_help) {
            toggleQuickHelp()
            return true
        }
        if (id == R.id.action_load_testmap) {
            // load test map (ID 0)
            val intent = Intent(applicationContext, Navigation::class.java)
            intent.putExtra(Navigation.INTENT_LOADMAPID, 0L)
            startActivityForResult(intent, NAVIGATION_REQUESTCODE)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleQuickHelp() {
        if (isHelpShown) {
            hideHelp()
        } else {
            layoutInflater.inflate(R.layout.start_help, layout)
            isHelpShown = true
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onHelpLayoutClick(dummy: View?) {
        hideHelp()
    }

    private fun hideHelp() {
        if (!isHelpShown) {
            return
        }
        layout!!.removeViewAt(layout!!.childCount - 1)
        isHelpShown = false
    }

    override fun onBackPressed() {
        if (isHelpShown) {
            hideHelp()
            return
        }
        // beendet offiziell die App
        finish()
    }

    // ////// for getting the images
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CHOOSE_FILE_REQUESTCODE && resultCode == RESULT_OK) {
            val EntzerrenActivity = Intent(applicationContext, Entzerren::class.java)

            // Quell-Uri lesen
            val srcUri = data!!.data
            try {
                // InputStream für Quelldatei erzeugen
                assert(srcUri != null)
                Log.d("Neue_Karte/onActivityResult", "Copying file '" + srcUri.toString() + "'")
                val inStream = this.contentResolver.openInputStream(srcUri!!)!!

                // Zieldatei erstellen
                val destFilename = "$cacheDir/$IMAGE_TARGET_FILENAME"
                val dest = FileOutputStream(File(destFilename))

                // Kopiere Daten von InputStream zu OutputStream
                inStream.copyTo(dest)

                // Stream auf Quelldatei schließen
                inStream.close()
                dest.close()
                EntzerrenActivity.putExtra(INTENT_IMAGEPATH, destFilename)
            } catch (e: IOException) {
                showErrorAlert(R.string.new_map_copy_error)
                e.printStackTrace()
            }
            startActivity(EntzerrenActivity)
        } else if (requestCode == TAKE_PICTURE_REQUESTCODE && resultCode == RESULT_OK) {
            val EntzerrenActivity = Intent(applicationContext, Entzerren::class.java)
            EntzerrenActivity.putExtra(INTENT_IMAGEPATH, "$cacheDir/$IMAGE_TARGET_FILENAME")
            startActivity(EntzerrenActivity)
        } else if (requestCode == NAVIGATION_REQUESTCODE && resultCode != RESULT_OK) {
            // Die Navigation hat einen Fehler zurückgegeben.

            // Öffne einen AlertDialog
            showErrorAlert(if (resultCode == 0) R.string.navigation_map_not_found else resultCode)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onNewMapClick(dummy: View?) {
        showNewMapPopup()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onCameraClick(dummy: View?) {
        // Intent erzeugen, der Standard-Android-Kamera startet
        val photoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val destFile = File("$cacheDir/$IMAGE_TARGET_FILENAME")
        try {
            destFile.createNewFile()
        } catch (ex: IOException) {
            // TODO: handle somehow!
        }
        val photoDestUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", destFile)
        photoIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoDestUri)
        photoIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@Start, arrayOf(Manifest.permission.CAMERA), 0)
            return
        }
        // Hack for older Android versions: need to explicitly grant permission or Camera app will crash
        val intentTargets = packageManager.queryIntentActivities(photoIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (intentTarget in intentTargets) {
            // For some reason, even read permission is required
            grantUriPermission(intentTarget.activityInfo.packageName, photoDestUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Activity starten und auf Ergebnis (Bild) warten
        try {
            startActivityForResult(photoIntent, TAKE_PICTURE_REQUESTCODE)
        } catch (e: ActivityNotFoundException) {
            showToast("No camera application available!")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onFilechooserClick(dummy: View?) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        val i = Intent.createChooser(intent, "File")
        startActivityForResult(i, CHOOSE_FILE_REQUESTCODE)
    }

    private fun showNewMapPopup() {
        newMapPopup!!.show()
        isPopupOpen = true
        val win = newMapPopup!!.window
        win!!.setContentView(R.layout.newmap)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val info = menuInfo as AdapterContextMenuInfo
        val position = info.position
        var header: String
        if (!noMaps) {
            header = positionIdList[position].mapname
            if (header.isEmpty()) {
                header = resources.getString(R.string.navigation_const_name_of_unnamed_maps)
            }
            val rename = getString(R.string.start_context_rename)
            val delete = getString(R.string.start_context_delete)
            menu.add(0, v.id, 0, rename)
            menu.add(0, v.id, 0, delete)
            menu.setHeaderTitle(header)
        }
    }

    private fun deleteMap(map: TrackDBEntry) {
        TrackDB.main.delete(map)
        val basefile = MapEverApp.getAbsoluteFilePath(map.identifier.toString())
        File(basefile).delete()
        File(basefile + MapEverApp.THUMB_EXT).delete()
    }

    private fun renameMap(map: TrackDBEntry, newName: String) {
        map.mapname = newName
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val position = info.position
        val rename = getString(R.string.start_context_rename)
        val delete = getString(R.string.start_context_delete)
        var mapName = positionIdList[position].mapname
        if (mapName.isEmpty()) {
            mapName = resources.getString(R.string.navigation_const_name_of_unnamed_maps)
        }
        when {
            item.title === rename -> {
                val alert = AlertDialog.Builder(this)
                alert.setTitle(R.string.navigation_rename_map)
                val input = EditText(this)
                input.setText(mapName)
                alert.setView(input)
                alert.setPositiveButton(R.string.navigation_rename_map_rename) { _, _ ->
                    val newName = input.editableText.toString()
                    renameMap(positionIdList[info.position], newName)
                    showToast(resources.getString(R.string.start_context_rename_success))
                }
                alert.setNegativeButton(R.string.navigation_rename_map_cancel
                ) { dialog, _ -> dialog.cancel() }
                val alertDialog = alert.create()
                alertDialog.show()
            }
            item.title === delete -> {
                val alert = AlertDialog.Builder(this)
                alert.setIcon(android.R.drawable.ic_dialog_alert)
                alert.setTitle(R.string.start_context_delete)
                alert.setMessage(R.string.start_context_delete_confirmation_msg)
                alert.setPositiveButton(android.R.string.yes) { _, _ ->
                    deleteMap(positionIdList[info.position])
                    // hack since refreshMapGrid does not work
                    val intent = intent
                    finish()
                    startActivity(intent)
                }
                alert.setNegativeButton(android.R.string.no, null)
                alert.show()
            }
            else -> {
                return false
            }
        }
        return true
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showErrorAlert(stringResId: Int) {
        // Erstelle entsprechenden AlertDialog
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.general_error_title)
        builder.setMessage(stringResId)
        val dialog = builder.create()

        // Dialog anzeigen
        dialog.show()

        // Wenn der Dialog geschlossen wird
        // dialog.setOnCancelListener(new OnCancelListener() {
        // public void onCancel(DialogInterface dialog) {
        // }
        // });
    }

    companion object {
        // Requestcodes für Neue Karte-Aktionen
        private const val CHOOSE_FILE_REQUESTCODE = 1337
        private const val TAKE_PICTURE_REQUESTCODE = 42

        // Requestcode für Aufruf der Navigation (Rückgabewert bei Fehler)
        private const val NAVIGATION_REQUESTCODE = 413
        private const val IMAGE_TARGET_FILENAME = MapEverApp.TEMP_IMAGE_FILENAME

        // image path
        const val INTENT_IMAGEPATH = "de.hu_berlin.informatik.spws2014.mapever.Start.NewImagePath"
        const val INTENT_EXIT = "de.hu_berlin.informatik.spws2014.mapever.Start.Exit"

        // returns the size of the grid's tiles in px
        var thumbnailSize = 120 * DisplayMetrics.DENSITY_DEFAULT
            private set

    }
}
