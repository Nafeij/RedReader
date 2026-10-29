/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.quantumbadger.redreader.cache.CacheRequest.RequestFailureType
import org.quantumbadger.redreader.common.General.readWholeStreamAsUTF8
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * Makes a still image of a video on v.redd.it from its first frame.
 *
 * Reddit's own still images of videos embedded in comments can't be used, as they're only
 * available from signed URLs which the API doesn't provide. They show the first frame of the
 * video in any case.
 *
 * Only the start of the smallest version of the video is downloaded: its headers, and its
 * first fragment, which is normally a few tens of kilobytes.
 */
object RedditVideoFirstFrame {

	private const val TAG = "RedditVideoFirstFrame"

	// The first request downloads this much of the video, which normally includes its first
	// fragment. If not, the rest of the fragment is downloaded, up to the second limit.
	private const val INITIAL_DOWNLOAD_BYTES = 64 * 1024
	private const val MAX_DOWNLOAD_BYTES = 256 * 1024

	// Used if the playlist doesn't give the size of the video
	private const val DEFAULT_WIDTH_PX = 320
	private const val DEFAULT_HEIGHT_PX = 180

	class Frame(
		@JvmField val bitmap: Bitmap,
		@JvmField val durationMs: Long?
	)

	@JvmStatic
	fun playlistUrl(videoId: String) = UriString("https://v.redd.it/$videoId/DASHPlaylist.mpd")

	/**
	 * Called with the video's playlist, on a thread which may block. If the first frame can't
	 * be decoded, a blank image the size of the video is used instead, so that the video can
	 * still be displayed, and played.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun decode(
		context: Context,
		videoId: String,
		playlist: InputStream,
		maxWidthPx: Int,
		maxHeightPx: Int
	): Frame {

		val mpd = parseMPD(readWholeStreamAsUTF8(playlist))
		val video = mpd.lowestBandwidthVideo ?: throw IOException("No video in playlist")

		val frame = try {
			decodeFirstFrame(context, UriString("https://v.redd.it/$videoId/${video.filename}"))
		} catch (e: Exception) {
			Log.e(TAG, "Failed to decode the first frame of $videoId", e)
			null
		}

		val bitmap = frame?.let { scaleToFitWithin(it, maxWidthPx, maxHeightPx) }
			?: blank(video.width ?: DEFAULT_WIDTH_PX, video.height ?: DEFAULT_HEIGHT_PX)

		return Frame(bitmap, mpd.durationMs)
	}

	/**
	 * Returns the offset of the end of the first media data box ("mdat") in an MP4 file, or
	 * null if its header isn't within the data. In the fragmented files which Reddit uses, the
	 * boxes before it describe the video and its first fragment, so the data up to this point
	 * is a complete video by itself.
	 */
	internal fun findFirstFragmentEnd(data: ByteArray): Long? {

		var offset = 0L

		while (offset + 8 <= data.size) {

			val start = offset.toInt()
			var size = readUnsigned(data, start, 4)
			val type = String(data, start + 4, 4, Charsets.US_ASCII)

			if (size == 1L) {
				// The size is in the following eight bytes
				if (offset + 16 > data.size) {
					return null
				}
				size = readUnsigned(data, start + 8, 8)

			} else if (size == 0L) {
				// The box extends to the end of the file, whose size is unknown
				return null
			}

			if (size < 8) {
				return null
			}

			if (type == "mdat") {
				return offset + size
			}

			offset += size
		}

		return null
	}

	private fun readUnsigned(data: ByteArray, start: Int, length: Int): Long {
		var result = 0L
		for (i in start until start + length) {
			result = (result shl 8) or (data[i].toLong() and 0xFF)
		}
		return result
	}

	private fun decodeFirstFrame(context: Context, url: UriString): Bitmap? {

		var data = download(context, url, 0, INITIAL_DOWNLOAD_BYTES)

		val fragmentEnd = findFirstFragmentEnd(data)

		if (fragmentEnd != null && fragmentEnd > data.size && fragmentEnd <= MAX_DOWNLOAD_BYTES) {
			data += download(context, url, data.size, fragmentEnd.toInt() - data.size)
		}

		val file = File.createTempFile("video_first_frame", ".mp4", context.cacheDir)

		try {
			file.writeBytes(data)

			val retriever = MediaMetadataRetriever()

			try {
				retriever.setDataSource(file.absolutePath)
				return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
			} finally {
				retriever.release()
			}

		} finally {
			file.delete()
		}
	}

	private fun download(context: Context, url: UriString, start: Int, length: Int): ByteArray {

		val request = HTTPBackend.backend.prepareRequest(
			context,
			HTTPBackend.RequestDetails(url, null)
		)

		request.addHeader("Range", "bytes=$start-${start + length - 1}")

		var result: ByteArray? = null
		var error: IOException? = null

		request.executeInThisThread(object : HTTPBackend.Listener {

			override fun onError(
				failureType: RequestFailureType,
				exception: Throwable?,
				httpStatus: Int?,
				body: FailedRequestBody?
			) {
				error = IOException("Failed to download $url ($failureType, $httpStatus)", exception)
			}

			override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {

				// A server which doesn't support ranges sends the whole file, which is only of
				// use if the start of the file was wanted
				if (start > 0 && bodyBytes != null && bodyBytes > length) {
					error = IOException("Range requests not supported for $url")
					return
				}

				result = body?.let { readAtMost(it, length) } ?: ByteArray(0)
			}
		})

		error?.let { throw it }

		return result ?: throw IOException("No response for $url")
	}

	private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {

		val output = ByteArrayOutputStream()
		val buffer = ByteArray(8192)

		while (output.size() < maxBytes) {
			val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
			if (read < 0) {
				break
			}
			output.write(buffer, 0, read)
		}

		return output.toByteArray()
	}

	private fun scaleToFitWithin(bitmap: Bitmap, maxWidthPx: Int, maxHeightPx: Int): Bitmap {

		val scale = minOf(
			maxWidthPx.toDouble() / bitmap.width,
			maxHeightPx.toDouble() / bitmap.height
		)

		if (scale >= 1.0) {
			return bitmap
		}

		return bitmap.scale(
			max(1, (bitmap.width * scale).toInt()),
			max(1, (bitmap.height * scale).toInt())
		)
	}

	private fun blank(widthPx: Int, heightPx: Int): Bitmap {
		return createBitmap(max(1, widthPx), max(1, heightPx), Bitmap.Config.RGB_565)
			.apply { eraseColor(Color.BLACK) }
	}
}
