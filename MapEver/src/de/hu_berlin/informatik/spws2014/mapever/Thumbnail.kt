/* Copyright (C) 2014,2015 Philipp Lenk
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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import java.io.FileOutputStream
import java.io.IOException

object Thumbnail {
    /**
     * Given the filename and path of an existing image, creates a scaled down
     * version of it and saves it as output_filename
     * (when output_filename parameter is missing this will be /path/to/image/name_thumb.png)
     * (Filetype is fixed to png right now, I could adjust this if desired)
     *
     * @param input_filename Path and filename of an existing image
     * @param output_filename Path and filename of the thumbnail(defaults to /path/to/image/name_thumb.png)
     * @param thumb_width Desired thumbnail width
     * @param thumb_height Desired thumbnail height
     */
    @Throws(IOException::class)
    fun generate(input_filename: String, output_filename: String?, thumb_width: Int, thumb_height: Int) {
        val sample_size = get_best_sample_size(input_filename, thumb_width, thumb_height)
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = false
        options.inSampleSize = sample_size
        val thumb = ThumbnailUtils.extractThumbnail(
                BitmapFactory.decodeFile(input_filename, options),
                thumb_width, thumb_height
        )
        val fos = FileOutputStream(output_filename)
        thumb.compress(Bitmap.CompressFormat.PNG, 100, fos)
        fos.close()
    }

    @Throws(IOException::class)
    fun generate(input_filename: String, thumb_width: Int, thumb_height: Int) {
        generate(input_filename, get_thumbnail_filename(input_filename), thumb_width, thumb_height)
    }

    private fun get_best_sample_size(filename: String, _thumb_width: Int, _thumb_height: Int): Int {
        //Decode to get image width/height.
        val thumb_width = Math.max(_thumb_width, 16)
        val thumb_height = Math.max(_thumb_height, 16)
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(filename, options)
        var current_width = options.outWidth
        var current_height = options.outHeight
        var current_sample_size = 1

        //increase sample size until it produces an image that is too small
        while (current_width >= thumb_width && current_height >= thumb_height) {
            current_width /= 2
            current_height /= 2
            current_sample_size *= 2
        }
        return current_sample_size / 2 //last size producing a image >= the desired
    }

    private fun get_thumbnail_filename(original_filename: String): String {
        return strip_extension(original_filename) + "_thumb.png"
    }

    private fun strip_extension(filename: String): String {
        return if (filename.lastIndexOf(".") == -1) filename else filename.substring(0, filename.lastIndexOf("."))
    }
}