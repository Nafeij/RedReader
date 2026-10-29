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
package org.quantumbadger.redreader.test.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.image.RedditVideoFirstFrame
import org.quantumbadger.redreader.image.parseMPD
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RedditVideoFirstFrameTest {

	private fun loadMPD(videoId: String) = parseMPD(
		File("src/test/resources/mpd/comment_video_$videoId.mpd").readText()
	)

	@Test
	fun lowestBandwidthVideo() {

		// Two versions, at 220p and 270p, with no audio
		loadMPD("46b22pf54zuh1").let {
			assertEquals("CMAF_220.mp4", it.lowestBandwidthVideo?.filename)
			assertEquals(392, it.lowestBandwidthVideo?.width)
			assertEquals(220, it.lowestBandwidthVideo?.height)
		}

		// Five versions, from 220p to 720p, with audio
		loadMPD("1l6gd1l0m6yh1").let {
			assertEquals("CMAF_220.mp4", it.lowestBandwidthVideo?.filename)
			assertEquals(236, it.lowestBandwidthVideo?.width)
			assertEquals(220, it.lowestBandwidthVideo?.height)
		}
	}

	@Test
	fun duration() {
		assertEquals(2200L, loadMPD("46b22pf54zuh1").durationMs)
		assertEquals(3200L, loadMPD("1l6gd1l0m6yh1").durationMs)
	}

	private class Mp4Builder {

		private val output = ByteArrayOutputStream()

		fun box(type: String, contentLength: Int) = apply {
			val size = contentLength + 8
			output.write(byteArrayOf(
				(size ushr 24).toByte(),
				(size ushr 16).toByte(),
				(size ushr 8).toByte(),
				size.toByte()))
			output.write(type.toByteArray(Charsets.US_ASCII))
			output.write(ByteArray(contentLength))
		}

		fun boxHeader(type: String, size: Long, largeSize: Boolean = false) = apply {
			val sizeBytes = if (largeSize) 1L else size
			for (shift in intArrayOf(24, 16, 8, 0)) {
				output.write((sizeBytes ushr shift).toInt())
			}
			output.write(type.toByteArray(Charsets.US_ASCII))
			if (largeSize) {
				for (shift in 56 downTo 0 step 8) {
					output.write((size ushr shift).toInt())
				}
			}
		}

		fun build(): ByteArray = output.toByteArray()
	}

	@Test
	fun firstFragmentEnd() {

		// The layout of Reddit's videos: headers, an index, then the first fragment
		val video = Mp4Builder()
			.box("ftyp", 16)
			.box("moov", 900)
			.box("sidx", 36)
			.box("moof", 300)
			.box("mdat", 20000)
			.box("moof", 300)
			.box("mdat", 20000)
			.build()

		val firstFragmentEnd = 24L + 908 + 44 + 308 + 20008

		assertEquals(firstFragmentEnd, RedditVideoFirstFrame.findFirstFragmentEnd(video))

		// Only the start of the video has been downloaded, but that includes the header of
		// the first fragment's data
		assertEquals(
			firstFragmentEnd,
			RedditVideoFirstFrame.findFirstFragmentEnd(video.copyOf(1300))
		)

		// Not enough has been downloaded to find the first fragment
		assertNull(RedditVideoFirstFrame.findFirstFragmentEnd(video.copyOf(1000)))
	}

	@Test
	fun firstFragmentEndWithLargeSize() {

		val video = Mp4Builder()
			.box("ftyp", 16)
			.box("moov", 100)
			.boxHeader("mdat", 5_000_000_000L, largeSize = true)
			.build()

		assertEquals(24L + 108 + 5_000_000_000L, RedditVideoFirstFrame.findFirstFragmentEnd(video))
	}

	@Test
	fun firstFragmentEndUnknown() {

		// A size of zero means the box extends to the end of the file
		val video = Mp4Builder()
			.box("ftyp", 16)
			.boxHeader("mdat", 0)
			.build()

		assertNull(RedditVideoFirstFrame.findFirstFragmentEnd(video))
	}
}
