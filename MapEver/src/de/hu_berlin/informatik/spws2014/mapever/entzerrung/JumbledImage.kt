/* Copyright (C) 2014,2015 Philipp Lenk, Jan Müller
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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log

object JumbledImage {
    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Double
    {
        val dx = x1.toDouble() - x2.toDouble()
        val dy = y1.toDouble() - y2.toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    /**
     * Given a distorted image and four corners marking the "intersting" segment, creates a corrected version
     * of that segment and returns it.
     *
     * This may take some time and should therefore be started asynchronous if possible ;-)
     *
     * Currently the image size has to be chosen very carefully, because the way this is written requires
     * Heap Memory for 2 full copies. I am working on a more memory saving version that does some caching
     * to disk, but its currently unbearably slow (Mostly due to Bitmaps getPixel.
     * The moment i use getPixels ram usage doubles...(Oh, and i have a slow bilinear filtering version, too)).
     * Would anyone object to a JNI solution(Let me code C++, pretty please with sugar on top?)?
     * Whom am i kidding, no one will read this anyway...
     *
     * @param jumbled A Bitmap containing the jumbled fragment. Beware, this will be destroyed!
     * @param corners The four corners as a one dimensional array with coordinates in the following order:
     * {x0,y0,x1,y1,x2,y2,x3,y3}
     * @return A Bitmap containing the corrected segment
     */
    fun transform(jumbled: Bitmap, corners: FloatArray): Bitmap {
        Log.d("BITMAP", "" + jumbled.width + " " + jumbled.height)
        Log.d("FLOAT", "pre sort: " + corners[0] + " " + corners[1] + " " + corners[2] + " " + corners[3] + " " + corners[4] + " " + corners[5] + " " + corners[6] + " " + corners[7])

        // Sort corners clockwise
        sort_corners(corners)
        Log.d("FLOAT", "post sort: " + corners[0] + " " + corners[1] + " " + corners[2] + " " + corners[3] + " " + corners[4] + " " + corners[5] + " " + corners[6] + " " + corners[7])


        //The final image size is average(width) X average(height) of the original image
        val dest_width = (dist(corners[0], corners[1], corners[2], corners[3]) +  //top length
                dist(corners[6], corners[7], corners[4], corners[5]) //bottom length
                ).toInt() / 2
        val dest_height = (dist(corners[0], corners[1], corners[6], corners[7]) +  //left length
                dist(corners[2], corners[3], corners[4], corners[5]) //right length
                ).toInt() / 2
        val mapped_corners = floatArrayOf(0f, 0f,
                dest_width.toFloat(), 0f,
                dest_width.toFloat(), dest_height.toFloat(), 0f, dest_height
                .toFloat())
        val m = Matrix()
        m.setPolyToPoly(mapped_corners, 0, corners, 0, 4)

        // get the image pixels as an array for faster processing
        val src_pixels = IntArray(jumbled.width * jumbled.height)
        val src_width = jumbled.width
        jumbled.getPixels(src_pixels, 0, jumbled.width, 0, 0, jumbled.width, jumbled.height)
        val conf = jumbled.config
        val pixels = IntArray(dest_width * dest_height)
        val point = FloatArray(2)
        for (y in 0 until dest_height) {
            for (x in 0 until dest_width) {
                point[0] = x.toFloat()
                point[1] = y.toFloat()
                m.mapPoints(point)
                pixels[y * dest_width + x] = computeColor(src_pixels, src_width, point)
            }
        }
        return Bitmap.createBitmap(pixels, dest_width, dest_height, conf)

        // return null;
    }

    private fun computeColor(src: IntArray, stride: Int, pos: FloatArray): Int {
        // trivial, not-interpolated one:
        return src[pos[0].toInt() + stride * pos[1].toInt()]
    }

    private fun sort_corners(unsorted: FloatArray) {
        assert(unsorted.size == 8)
        val center = floatArrayOf((unsorted[0] + unsorted[2] + unsorted[4] + unsorted[6]) / 4,
                                  (unsorted[1] + unsorted[3] + unsorted[5] + unsorted[7]) / 4)

        // clockwise bubble sort, yeahy ;-)
        var swapped: Boolean
        do {
            swapped = false
            for (i in 0..2) {
                if (!is_clockwise_turn(floatArrayOf(unsorted[2 * i], unsorted[2 * i + 1]), floatArrayOf(unsorted[2 * i + 2], unsorted[2 * i + 3]),
                                center)) {
                    var tmp = unsorted[2 * i]
                    unsorted[2 * i] = unsorted[2 * i + 2]
                    unsorted[2 * i + 2] = tmp
                    tmp = unsorted[2 * i + 1]
                    unsorted[2 * i + 1] = unsorted[2 * i + 3]
                    unsorted[2 * i + 3] = tmp
                    swapped = true
                }
            }
        } while (swapped)
    }

    private fun is_clockwise_turn(first: FloatArray, second: FloatArray, center: FloatArray): Boolean {
        assert(first.size == second.size && second.size == center.size && center.size == 2)
        return (first[0] - center[0]) * (second[1] - center[1]) - (second[0] - center[0]) * (first[1] - center[1]) > 0
    }
}