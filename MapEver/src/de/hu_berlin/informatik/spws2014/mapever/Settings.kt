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

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class Settings : AppCompatActivity() {
    // Ignore deprecation warnings (there are no API 10 compatible alternatives)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
    }

    companion object {
        private const val key_quickHelp = "pref_quick_help"
        private const val key_livMultitouch = "pref_liv_multitouch"
        private const val key_debugMode = "pref_debugmode"
        // Preference getters
        /**
         * Returns true if quick help button should be visible in Action Bar.
         *
         * @param context Just use 'this'
         */
        @JvmStatic
        fun getPreference_quickHelp(context: Context?): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key_quickHelp, false)
        }

        /**
         * Returns true if multitouch in LIV should be enabled.
         *
         * @param context Just use 'this'
         */
        @JvmStatic
        fun getPreference_livMultitouch(context: Context?): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key_livMultitouch, false)
        }

        /**
         * Returns true if debug mode should be activated.
         *
         * @param context Just use 'this'
         */
        @JvmStatic
        fun getPreference_debugMode(context: Context?): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key_debugMode, false)
        }
    }
}