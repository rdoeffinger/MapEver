/* Copyright (C) 2014,2015 Björn Stelter, Florian Kempf
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

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import de.hu_berlin.informatik.spws2014.mapever.Settings.Companion.getPreference_quickHelp

abstract class BaseActivity : AppCompatActivity() {
    // ÜberUns popup
    private var aboutUsPopup: AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ////////// hier wird das popup fenster erstellt ///////////////
        aboutUsPopup = AlertDialog.Builder(this@BaseActivity).create()

        // /////////sobald man irgendwo ausserhalb den bildschirm beruehrt
        // /////////wird das popup geschlossen
        aboutUsPopup!!.setCanceledOnTouchOutside(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Show quick help button only in Action Bar if the corresponding setting is activated
        val item = menu.findItem(R.id.action_quick_help)
        if (item != null) {
            if (getPreference_quickHelp(this)) {
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            } else {
                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == R.id.action_about) {
            showAboutUsPopup()
            return true
        } else if (id == R.id.action_settings) {
            // open settings
            val settings = Intent(applicationContext, Settings::class.java)
            startActivity(settings)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAboutUsPopup() {
        aboutUsPopup!!.show()
        val win = aboutUsPopup!!.window
        win!!.setContentView(R.layout.aboutus)
    }
}
