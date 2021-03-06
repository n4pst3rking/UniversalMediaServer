/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import java.awt.Font;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.FileTranscodeVirtualFolder;
import net.pms.dlna.InputFile;
import net.pms.formats.Format;
import net.pms.formats.v2.SubtitleType;
import net.pms.formats.v2.SubtitleUtils;
import net.pms.io.OutputParams;
import net.pms.io.PipeIPCProcess;
import net.pms.io.PipeProcess;
import net.pms.io.ProcessWrapper;
import net.pms.io.ProcessWrapperImpl;
import net.pms.io.StreamModifier;
import net.pms.io.OutputTextLogger;
import net.pms.network.HTTPResource;
import net.pms.util.CodecUtil;
import net.pms.util.FileUtil;
import net.pms.util.PlayerUtil;
import net.pms.util.ProcessUtil;
import org.apache.commons.io.FileUtils;
import static net.pms.util.StringUtil.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mozilla.universalchardet.Constants.CHARSET_UTF_8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Pure FFmpeg video player.
 *
 * Design note:
 *
 * Helper methods that return lists of <code>String</code>s representing
 * options are public to facilitate composition e.g. a custom engine (plugin)
 * that uses tsMuxeR for videos without subtitles and FFmpeg otherwise needs to
 * compose and call methods on both players.
 *
 * To avoid API churn, and to provide wiggle room for future functionality, all
 * of these methods take the same arguments as launchTranscode (and the same
 * first four arguments as finalizeTranscoderArgs) even if one or more of the
 * parameters are unused e.g.:
 *
 *     public List<String> getAudioBitrateOptions(
 *         String filename,
 *         DLNAResource dlna,
 *         DLNAMediaInfo media,
 *         OutputParams params
 *     )
 */
public class FFMpegVideo extends Player {
	private static final Logger LOGGER = LoggerFactory.getLogger(FFMpegVideo.class);
	private static final String DEFAULT_QSCALE = "3";
	private static final String SUB_DIR = "subs";

	public FFMpegVideo() {
	}

	@Deprecated
	public FFMpegVideo(PmsConfiguration configuration) {
		this();
	}

	// FIXME we have an id() accessor for this; no need for the field to be public
	@Deprecated
	public static final String ID = "ffmpegvideo";

	/**
	 * Returns a list of strings representing the rescale options for this transcode i.e. the ffmpeg -vf
	 * options used to show subtitles in either SSA/ASS or picture-based format and resize a video that's too wide and/or high for the specified renderer.
	 * If the renderer has no size limits, or there's no media metadata, or the video is within the renderer's
	 * size limits, an empty list is returned.
	 *
	 * @param dlna
	 * @param media metadata for the DLNA resource which is being transcoded
	 * @param params
	 * @return a {@link List} of <code>String</code>s representing the rescale options for this video,
	 * or an empty list if the video doesn't need to be resized.
	 * @throws java.io.IOException
	 */
	public List<String> getVideoFilterOptions(DLNAResource dlna, DLNAMediaInfo media, OutputParams params) throws IOException {
		List<String> videoFilterOptions = new ArrayList<>();
		String filterOption = "-vf";
		ArrayList<String> filterChain = new ArrayList<>();
		final RendererConfiguration renderer = params.mediaRenderer;

		boolean isMediaValid = media != null && media.isMediaparsed() && media.getHeight() != 0;
		boolean isResolutionTooHighForRenderer = renderer.isMaximumResolutionSpecified() && isMediaValid && // renderer defines a max width/height
			(
				media.getWidth() > renderer.getMaxVideoWidth() ||
				media.getHeight() > renderer.getMaxVideoHeight()
			);

		if (!isDisableSubtitles(params) && !(dlna.getPlayer() instanceof WebPlayer)) {
			StringBuilder subsFilter = new StringBuilder();
			if (params.sid.getType().isText()) {
				String subsFilename = null;
				if (configuration.isFFmpegFontConfig()) {
					subsFilename = getSubtitles(dlna, media, params, configuration).getAbsolutePath();
				} else {
					subsFilename = params.sid.isEmbedded() ? dlna.getSystemName() : params.sid.getExternalFile().getAbsolutePath();
				}

				if (subsFilename != null) {
					StringBuilder s = new StringBuilder();
					CharacterIterator it = new StringCharacterIterator(subsFilename);
					for (char ch = it.first(); ch != CharacterIterator.DONE; ch = it.next()) {
						switch (ch) {
							case ':':
								s.append("\\\\:");
								break;
							case '\\':
								s.append("/");
								break;
							case ']':
							case '[':
								s.append("\\");
							default:
								s.append(ch);
								break;
						}
					}

					subsFilename = s.toString();
					subsFilename = subsFilename.replace(",", "\\,");
					subsFilter.append("subtitles=").append(subsFilename);
					if (params.sid.isExternal() && params.sid.getType() != SubtitleType.ASS || configuration.isFFmpegFontConfig()) {
						subsFilter.append(":384x288");
						if (!params.sid.isExternalFileUtf8()) { // Set the input subtitles character encoding if not UTF-8
							String encoding = isNotBlank(configuration.getSubtitlesCodepage()) ?
									configuration.getSubtitlesCodepage() : params.sid.getExternalFileCharacterSet() != null ?
									params.sid.getExternalFileCharacterSet() : null;
							if (encoding != null) {
								subsFilter.append(":").append(encoding);
							}
						}
					} else if (params.sid.isEmbedded()) {
						subsFilter.append(":si=").append(media.getSubtitleTracksList().indexOf(params.sid));
					}
				}

			} else if (params.sid.getType().isPicture()) {
				filterOption = "-filter_complex";
				if (params.sid.getId() < 100) {
					// Embedded
					subsFilter.append("[0:v][0:s:").append(media.getSubtitleTracksList().indexOf(params.sid)).append("]overlay");
				} else {
					// External
					videoFilterOptions.add("-i");
					videoFilterOptions.add(params.sid.getExternalFile().getAbsolutePath());
					subsFilter.append("[0:v][1:s]overlay"); // this assumes the sub file is single-language
				}
			}

			if (isNotBlank(subsFilter)) {
				filterChain.add(subsFilter.toString());
				// based on https://trac.ffmpeg.org/ticket/2067
				if (params.timeseek > 0) {
					videoFilterOptions.add("-copyts");
					videoFilterOptions.add("-copypriorss");
					videoFilterOptions.add("0");
					videoFilterOptions.add("-avoid_negative_ts");
					videoFilterOptions.add("1");
					videoFilterOptions.add("-af");
					videoFilterOptions.add("asetpts=PTS-" + params.timeseek + "/TB");
					filterChain.add("setpts=PTS-" + params.timeseek + "/TB");
				}
			}
		}

		String overrideVF = renderer.getFFmpegVideoFilterOverride();

		if (StringUtils.isNotEmpty(overrideVF)) {
			filterChain.add(overrideVF);
		} else {
			/**
			 * Make sure the aspect ratio is 16/9 if the renderer needs it.
			 */

			int scaleWidth = 0;
			int scaleHeight = 0;
			if (media.getWidth() > 0 && media.getHeight() > 0) {
				scaleWidth = media.getWidth();
				scaleHeight = media.getHeight();
			}

			boolean keepAR = renderer.isKeepAspectRatio() &&
					!(
						media.getWidth() == 3840 && media.getHeight() <= 1080 ||
						media.getWidth() == 1920 && media.getHeight() == 2160
					) &&
					!"16:9".equals(media.getAspectRatioContainer());
			if (isResolutionTooHighForRenderer || (!renderer.isRescaleByRenderer() && renderer.isMaximumResolutionSpecified() && media.getWidth() < 720)) { // Do not rescale for SD video and higher
				filterChain.add(String.format("scale=iw*min(%1$d/iw\\,%2$d/ih):ih*min(%1$d/iw\\,%2$d/ih)", renderer.getMaxVideoWidth(), renderer.getMaxVideoHeight()));
				if (keepAR) {
					filterChain.add(String.format("pad=%1$d:%2$d:(%1$d-iw)/2:(%2$d-ih)/2", renderer.getMaxVideoWidth(), renderer.getMaxVideoHeight()));
				}
			} else if (keepAR && isMediaValid) {
				if ((media.getWidth() / (double) media.getHeight()) >= (16 / (double) 9)) {
					filterChain.add("pad=iw:iw/(16/9):0:(oh-ih)/2");
					scaleHeight = (int) Math.round(scaleWidth / (16 / (double) 9));
				} else {
					filterChain.add("pad=ih*(16/9):ih:(ow-iw)/2:0");
					scaleWidth  = (int) Math.round(scaleHeight * (16 / (double) 9));
				}

				scaleWidth  = convertToMod4(scaleWidth);
				scaleHeight = convertToMod4(scaleHeight);
				filterChain.add("scale=" + scaleWidth + ":" + scaleHeight);
			}
		}

		if (filterChain.size() > 0) {
			videoFilterOptions.add(filterOption);
			videoFilterOptions.add(StringUtils.join(filterChain, ", "));
		}

		return videoFilterOptions;
	}

	/**
	 * Returns a list of <code>String</code>s representing ffmpeg output
	 * options (i.e. options that define the output file's video codec,
	 * audio codec and container) compatible with the renderer's
	 * <code>TranscodeVideo</code> profile.
	 *
	 * @param dlna
	 * @param media the media metadata for the video being streamed. May contain unset/null values (e.g. for web videos).
	 * @param params output parameters
	 *
	 * @return a {@link List} of <code>String</code>s representing the FFmpeg output parameters for the renderer according
	 * to its <code>TranscodeVideo</code> profile.
	 */
	public synchronized List<String> getVideoTranscodeOptions(DLNAResource dlna, DLNAMediaInfo media, OutputParams params) {
		List<String> transcodeOptions = new ArrayList<>();
		final String filename = dlna.getSystemName();
		final RendererConfiguration renderer = params.mediaRenderer;
		String customFFmpegOptions = renderer.getCustomFFmpegOptions();

		if (renderer.isTranscodeToWMV() && !renderer.isXBOX()) { // WMV
			transcodeOptions.add("-c:v");
			transcodeOptions.add("wmv2");

			if (!customFFmpegOptions.contains("-c:a ")) {
				transcodeOptions.add("-c:a");
				transcodeOptions.add("wmav2");
			}

			transcodeOptions.add("-f");
			transcodeOptions.add("asf");
		} else { // MPEGPSMPEG2AC3, MPEGTSMPEG2AC3, MPEGTSH264AC3 or MPEGTSH264AAC
			final boolean isTsMuxeRVideoEngineEnabled = configuration.getEnginesAsList(PMS.get().getRegistry()).contains(TsMuxeRVideo.ID);

			// Output audio codec
			dtsRemux = isTsMuxeRVideoEngineEnabled &&
				configuration.isAudioEmbedDtsInPcm() &&
				params.aid != null &&
				params.aid.isDTS() &&
				!avisynth() &&
				renderer.isDTSPlayable();
			
			boolean isSubtitlesAndTimeseek = !isDisableSubtitles(params) && params.timeseek > 0;

			if (configuration.isAudioRemuxAC3() && params.aid != null && params.aid.isAC3() && !avisynth() && renderer.isTranscodeToAC3() && !isSubtitlesAndTimeseek) {
				// AC-3 remux
				if (!customFFmpegOptions.contains("-c:a ")) {
					transcodeOptions.add("-c:a");
					transcodeOptions.add("copy");
				}
			} else {
				if (dtsRemux) {
					// Audio is added in a separate process later
					transcodeOptions.add("-an");
				} else if (type() == Format.AUDIO) {
					// Skip
				} else if (renderer.isTranscodeToMPEGTSH264AAC()) {
					transcodeOptions.add("-c:a");
					transcodeOptions.add("aac");

					transcodeOptions.add("-strict");
					transcodeOptions.add("experimental");
				} else {
					if (!customFFmpegOptions.contains("-c:a ")) {
						transcodeOptions.add("-c:a");
						transcodeOptions.add("ac3");
					}
				}
			}

			InputFile newInput = null;
			if (filename != null) {
				newInput = new InputFile();
				newInput.setFilename(filename);
				newInput.setPush(params.stdin);
			}

			// Output video codec
			if (renderer.isTranscodeToMPEGTSH264AC3() || renderer.isTranscodeToMPEGTSH264AAC()) {
				transcodeOptions.add("-c:v");
				transcodeOptions.add("libx264");
				transcodeOptions.add("-preset");
				transcodeOptions.add("superfast");
				transcodeOptions.add("-level");
				transcodeOptions.add("31");
				transcodeOptions.add("-pix_fmt");
				transcodeOptions.add("yuv420p");
			} else if (!dtsRemux) {
				transcodeOptions.add("-c:v");
				transcodeOptions.add("mpeg2video");
			}

			// Output file format
			transcodeOptions.add("-f");
			if (dtsRemux) {
				transcodeOptions.add("mpeg2video");
			} else if (renderer.isTranscodeToMPEGTSMPEG2AC3() || renderer.isTranscodeToMPEGTSH264AC3() || renderer.isTranscodeToMPEGTSH264AAC()) { // MPEGTSMPEG2AC3, MPEGTSH264AC3 or MPEGTSH264AAC
				transcodeOptions.add("mpegts");
			} else { // default: MPEGPSMPEG2AC3
				transcodeOptions.add("vob");
			}
		}

		return transcodeOptions;
	}

	/**
	 * Returns the video bitrate spec for the current transcode according
	 * to the limits/requirements of the renderer and the user's settings.
	 *
	 * @param dlna
	 * @param media the media metadata for the video being streamed. May contain unset/null values (e.g. for web videos).
	 * @param params
	 * @return a {@link List} of <code>String</code>s representing the video bitrate options for this transcode
	 */
	public List<String> getVideoBitrateOptions(DLNAResource dlna, DLNAMediaInfo media, OutputParams params) {
		List<String> videoBitrateOptions = new ArrayList<>();

		int defaultMaxBitrates[] = getVideoBitrateConfig(configuration.getMaximumBitrate());
		int rendererMaxBitrates[] = new int[2];

		if (StringUtils.isNotEmpty(params.mediaRenderer.getMaxVideoBitrate())) {
			rendererMaxBitrates = getVideoBitrateConfig(params.mediaRenderer.getMaxVideoBitrate());
		}

		// Give priority to the renderer's maximum bitrate setting over the user's setting
		if (rendererMaxBitrates[0] > 0 && rendererMaxBitrates[0] < defaultMaxBitrates[0]) {
			defaultMaxBitrates = rendererMaxBitrates;
			LOGGER.trace("Using the video bitrate limit from the renderer config (" + rendererMaxBitrates[0] + ") which is lower than the one from the program settings (" + defaultMaxBitrates[0] + ")");
		} else {
			LOGGER.trace("Using the video bitrate limit from the program settings (" + defaultMaxBitrates[0] + ")");
		}

		if (params.mediaRenderer.getCBRVideoBitrate() == 0 && params.timeend == 0) {
			// Convert value from Mb to Kb
			defaultMaxBitrates[0] = 1000 * defaultMaxBitrates[0];

			// Halve it since it seems to send up to 1 second of video in advance
			defaultMaxBitrates[0] /= 2;

			LOGGER.trace("Halving the video bitrate limit to " + defaultMaxBitrates[0]);

			int bufSize = 1835;
			boolean bitrateLevel41Limited = false;

			/**
			 * Although the maximum bitrate for H.264 Level 4.1 is
			 * officially 50,000 kbit/s, some 4.1-capable renderers
			 * like the PS3 stutter when video exceeds roughly 31,250
			 * kbit/s.
			 *
			 * We also apply the correct buffer size in this section.
			 */
			if (params.mediaRenderer.isTranscodeToMPEGTSH264AC3() || params.mediaRenderer.isTranscodeToMPEGTSH264AAC()) {
				if (
					params.mediaRenderer.isH264Level41Limited() &&
					defaultMaxBitrates[0] > 31250
				) {
					defaultMaxBitrates[0] = 31250;
					bitrateLevel41Limited = true;
					LOGGER.trace("Adjusting the video bitrate limit to the H.264 Level 4.1-safe value of 31250");
				}
				bufSize = defaultMaxBitrates[0];
			} else {
				if (media.isHDVideo()) {
					bufSize = defaultMaxBitrates[0] / 3;
				}

				if (bufSize > 7000) {
					bufSize = 7000;
				}

				if (defaultMaxBitrates[1] > 0) {
					bufSize = defaultMaxBitrates[1];
				}

				if (params.mediaRenderer.isDefaultVBVSize() && rendererMaxBitrates[1] == 0) {
					bufSize = 1835;
				}
			}

			if (!bitrateLevel41Limited) {
				// Make room for audio
				if (dtsRemux) {
					defaultMaxBitrates[0] -= 1510;
				} else {
					defaultMaxBitrates[0] -= configuration.getAudioBitrate();
				}

				// Round down to the nearest Mb
				defaultMaxBitrates[0] = defaultMaxBitrates[0] / 1000 * 1000;

				LOGGER.trace("Adjusting the video bitrate limit to " + defaultMaxBitrates[0] + " to make room for audio");
			}

			// FFmpeg uses bytes for inputs instead of kbytes like MEncoder
			bufSize *= 1000;
			defaultMaxBitrates[0] *= 1000;

			videoBitrateOptions.add("-bufsize");
			videoBitrateOptions.add(String.valueOf(bufSize));

			videoBitrateOptions.add("-maxrate");
			videoBitrateOptions.add(String.valueOf(defaultMaxBitrates[0]));
		}

		if (!params.mediaRenderer.isTranscodeToMPEGTSH264AC3() && !params.mediaRenderer.isTranscodeToMPEGTSH264AAC()) {
			// Add MPEG-2 quality settings
			String mpeg2Options = configuration.getMPEG2MainSettingsFFmpeg();
			String mpeg2OptionsRenderer = params.mediaRenderer.getCustomFFmpegMPEG2Options();

			// Renderer settings take priority over user settings
			if (isNotBlank(mpeg2OptionsRenderer)) {
				mpeg2Options = mpeg2OptionsRenderer;
			} else if (mpeg2Options.contains("Automatic")) {
				mpeg2Options = "-g 5 -q:v 1 -qmin 2 -qmax 3";

				// It has been reported that non-PS3 renderers prefer keyint 5 but prefer it for PS3 because it lowers the average bitrate
				if (params.mediaRenderer.isPS3()) {
					mpeg2Options = "-g 25 -q:v 1 -qmin 2 -qmax 3";
				}

				if (mpeg2Options.contains("Wireless") || defaultMaxBitrates[0] < 70) {
					// Lower quality for 720p+ content
					if (media.getWidth() > 1280) {
						mpeg2Options = "-g 25 -qmax 7 -qmin 2";
					} else if (media.getWidth() > 720) {
						mpeg2Options = "-g 25 -qmax 5 -qmin 2";
					}
				}
			}
			String[] customOptions = StringUtils.split(mpeg2Options);
			videoBitrateOptions.addAll(new ArrayList<>(Arrays.asList(customOptions)));
		} else {
			// Add x264 quality settings
			String x264CRF = configuration.getx264ConstantRateFactor();

			// Remove comment from the value
			if (x264CRF.contains("/*")) {
				x264CRF = x264CRF.substring(x264CRF.indexOf("/*"));
			}

			if (x264CRF.contains("Automatic")) {
				x264CRF = "16";

				// Lower CRF for 720p+ content
				if (media.getWidth() > 720) {
					x264CRF = "19";
				}
			}
			videoBitrateOptions.add("-crf");
			videoBitrateOptions.add(x264CRF);
		}

		return videoBitrateOptions;
	}

	/**
	 * Returns the audio bitrate spec for the current transcode according
	 * to the limits/requirements of the renderer.
	 *
	 * @param dlna
	 * @param media the media metadata for the video being streamed. May contain unset/null values (e.g. for web videos).
	 * @param params
	 * @return a {@link List} of <code>String</code>s representing the audio bitrate options for this transcode
	 */
	public List<String> getAudioBitrateOptions(DLNAResource dlna, DLNAMediaInfo media, OutputParams params) {
		List<String> audioBitrateOptions = new ArrayList<>();

		audioBitrateOptions.add("-q:a");
		audioBitrateOptions.add(DEFAULT_QSCALE);

		return audioBitrateOptions;
	}

	protected boolean dtsRemux;
	protected boolean ac3Remux;

	@Override
	public int purpose() {
		return VIDEO_SIMPLEFILE_PLAYER;
	}

	@Override
	// TODO make this static so it can replace ID, instead of having both
	public String id() {
		return ID;
	}

	@Override
	public boolean isTimeSeekable() {
		return true;
	}

	@Override
	public boolean avisynth() {
		return false;
	}

	public String initialString() {
		String threads = " -threads 1";
		if (configuration.isFfmpegMultithreading()) {
			if (Runtime.getRuntime().availableProcessors() == configuration.getNumberOfCpuCores()) {
				threads = "";
			} else {
				threads = " -threads " + configuration.getNumberOfCpuCores();
			}
		}
		return threads;
	}

	@Override
	public String name() {
		return "FFmpeg";
	}

	@Override
	public int type() {
		return Format.VIDEO;
	}

	// unused; return this array for backwards-compatibility
	@Deprecated
	protected String[] getDefaultArgs() {
		List<String> defaultArgsList = new ArrayList<>();

		defaultArgsList.add("-loglevel");
		defaultArgsList.add("warning");

		String[] defaultArgsArray = new String[defaultArgsList.size()];
		defaultArgsList.toArray(defaultArgsArray);

		return defaultArgsArray;
	}

	private int[] getVideoBitrateConfig(String bitrate) {
		int bitrates[] = new int[2];

		if (bitrate.contains("(") && bitrate.contains(")")) {
			bitrates[1] = Integer.parseInt(bitrate.substring(bitrate.indexOf('(') + 1, bitrate.indexOf(')')));
		}

		if (bitrate.contains("(")) {
			bitrate = bitrate.substring(0, bitrate.indexOf('(')).trim();
		}

		if (isBlank(bitrate)) {
			bitrate = "0";
		}

		bitrates[0] = (int) Double.parseDouble(bitrate);

		return bitrates;
	}

	@Override
	@Deprecated
	public String[] args() {
		return getDefaultArgs(); // unused; return this array for for backwards compatibility
	}

	@Override
	public String mimeType() {
		return HTTPResource.VIDEO_TRANSCODE;
	}

	@Override
	public String executable() {
		return configuration.getFfmpegPath();
	}

	@Override
	public boolean isGPUAccelerationReady() {
		return false;
	}

	@Override
	public synchronized ProcessWrapper launchTranscode(
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params
	) throws IOException {
		final String filename = dlna.getSystemName();
		InputFile newInput = new InputFile();
		newInput.setFilename(filename);
		newInput.setPush(params.stdin);

		/*
		 * Check if the video track and the container report different aspect ratios
		 */
		boolean aspectRatiosMatch = true;
		if (
			media.getAspectRatioContainer() != null &&
			media.getAspectRatioVideoTrack() != null &&
			!media.getAspectRatioContainer().equals(media.getAspectRatioVideoTrack())
		) {
			aspectRatiosMatch = false;
		}

		/*
		 * FFmpeg uses multithreading by default, so provided that the
		 * user has not disabled FFmpeg multithreading and has not
		 * chosen to use more or less threads than are available, do not
		 * specify how many cores to use.
		 */
		int nThreads = 1;
		if (configuration.isFfmpegMultithreading()) {
			if (Runtime.getRuntime().availableProcessors() == configuration.getNumberOfCpuCores()) {
				nThreads = 0;
			} else {
				nThreads = configuration.getNumberOfCpuCores();
			}
		}

		List<String> cmdList = new ArrayList<>();
		RendererConfiguration renderer = params.mediaRenderer;
		boolean avisynth = avisynth();
		if (params.timeseek > 0) {
			params.waitbeforestart = 200;
		} else {
			params.waitbeforestart = 2500;
		}

		if (params.aid == null) {
			setAudioOutputParameters(media, params);
		}

		if (params.sid == null || (params.sid != null && StringUtils.isNotEmpty(params.sid.getLiveSubURL()))) {
			setSubtitleOutputParameters(filename, media, params);
		}

		cmdList.add(executable());

		// Prevent FFmpeg timeout
		cmdList.add("-y");

		cmdList.add("-loglevel");
		if (LOGGER.isTraceEnabled()) { // Set -loglevel in accordance with LOGGER setting
			cmdList.add("info"); // Could be changed to "verbose" or "debug" if "info" level is not enough
		} else {
			cmdList.add("fatal");
		}

		if (params.timeseek > 0) {
			cmdList.add("-ss");
			cmdList.add(String.valueOf((int) params.timeseek));
		}

		// Decoder threads
		if (nThreads > 0) {
			cmdList.add("-threads");
			cmdList.add(String.valueOf(nThreads));
		}

		final boolean isTsMuxeRVideoEngineEnabled = configuration.getEnginesAsList(PMS.get().getRegistry()).contains(TsMuxeRVideo.ID);

		ac3Remux = false;
		dtsRemux = false;

		if (configuration.isAudioRemuxAC3() && params.aid != null && params.aid.isAC3() && !avisynth() && renderer.isTranscodeToAC3()) {
			// AC-3 remux takes priority
			ac3Remux = true;
		} else {
			// Now check for DTS remux and LPCM streaming
			dtsRemux = isTsMuxeRVideoEngineEnabled &&
				configuration.isAudioEmbedDtsInPcm() &&
				params.aid != null &&
				params.aid.isDTS() &&
				!avisynth() &&
				params.mediaRenderer.isDTSPlayable();
		}

		String frameRateRatio = media.getValidFps(true);
		String frameRateNumber = media.getValidFps(false);

		// Input filename
		cmdList.add("-i");
		if (avisynth && !filename.toLowerCase().endsWith(".iso")) {
			File avsFile = AviSynthFFmpeg.getAVSScript(filename, params.sid, params.fromFrame, params.toFrame, frameRateRatio, frameRateNumber);
			cmdList.add(ProcessUtil.getShortFileNameIfWideChars(avsFile.getAbsolutePath()));
		} else {
			cmdList.add(filename);
		}

		// Apply any video filters and associated options. These should go
		// after video input is specified and before output streams are mapped.
		cmdList.addAll(getVideoFilterOptions(dlna, media, params));

		// Decide whether to defer to tsMuxeR or continue to use FFmpeg
		boolean deferToTsmuxer = true;
		String prependTraceReason = "Not muxing the video stream with tsMuxeR via FFmpeg because ";
		if (!configuration.isFFmpegMuxWithTsMuxerWhenCompatible()) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the user setting is disabled");
		}
		if (deferToTsmuxer == true && !configuration.getHideTranscodeEnabled() && dlna.isNoName() && (dlna.getParent() instanceof FileTranscodeVirtualFolder)) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the file is being played via a FFmpeg entry in the transcode folder.");
		}
		if (deferToTsmuxer == true && !params.mediaRenderer.isMuxH264MpegTS()) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the renderer does not support H.264 inside MPEG-TS.");
		}
		if (deferToTsmuxer == true && params.sid != null) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "we need to burn subtitles.");
		}
		if (deferToTsmuxer == true && avisynth()) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "we are using AviSynth.");
		}
		if (deferToTsmuxer == true && params.mediaRenderer.isH264Level41Limited() && !media.isVideoWithinH264LevelLimits(newInput, params.mediaRenderer)) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the video stream is not within H.264 level limits for this renderer.");
		}
		if (deferToTsmuxer == true && !media.isMuxable(params.mediaRenderer)) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the video stream is not muxable to this renderer");
		}
		if (deferToTsmuxer == true && !aspectRatiosMatch) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "we need to transcode to apply the correct aspect ratio.");
		}
		if (deferToTsmuxer == true && !params.mediaRenderer.isPS3() && filename.contains("WEB-DL")) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the version of tsMuxeR supported by this renderer does not support WEB-DL files.");
		}
		if (deferToTsmuxer == true && "bt.601".equals(media.getMatrixCoefficients())) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the colorspace probably isn't supported by the renderer.");
		}
		if (deferToTsmuxer == true && params.mediaRenderer.isKeepAspectRatio() && !"16:9".equals(media.getAspectRatioContainer())) {
			deferToTsmuxer = false;
			LOGGER.trace(prependTraceReason + "the renderer needs us to add borders so it displays the correct aspect ratio of " + media.getAspectRatioContainer() + ".");
		}
		if (deferToTsmuxer) {
			TsMuxeRVideo tv = new TsMuxeRVideo();
			params.forceFps = media.getValidFps(false);

			if (media.getCodecV() != null) {
				if (media.isH264()) {
					params.forceType = "V_MPEG4/ISO/AVC";
				} else if (media.getCodecV().startsWith("mpeg2")) {
					params.forceType = "V_MPEG-2";
				} else if (media.getCodecV().equals("vc1")) {
					params.forceType = "V_MS/VFW/WVC1";
				}
			}

			return tv.launchTranscode(dlna, media, params);
		}

		// Map the output streams if necessary
		if (media.getAudioTracksList().size() > 1) {
			// Set the video stream
			cmdList.add("-map");
			cmdList.add("0:v");

			// Set the proper audio stream
			cmdList.add("-map");
			cmdList.add("0:a:" + (media.getAudioTracksList().indexOf(params.aid)));
		}

		// Now configure the output streams

		// Encoder threads
		if (nThreads > 0) {
			cmdList.add("-threads");
			cmdList.add(String.valueOf(nThreads));
		}

		if (params.timeend > 0) {
			cmdList.add("-t");
			cmdList.add(String.valueOf(params.timeend));
		}

		cmdList.addAll(getVideoBitrateOptions(dlna, media, params));

		// add audio bitrate options
		// TODO: Integrate our (more comprehensive) code with this function
		// from PMS to make keeping synchronised easier.
		// Until then, leave the following line commented out.
		// cmdList.addAll(getAudioBitrateOptions(dlna, media, params));

		String customFFmpegOptions = renderer.getCustomFFmpegOptions();

		// Audio bitrate
		if (!ac3Remux && !dtsRemux && !(type() == Format.AUDIO)) {
			int channels = 0;
			if (renderer.isTranscodeToWMV() && !renderer.isXBOX()) {
				channels = 2;
			} else if (params.aid != null && params.aid.getAudioProperties().getNumberOfChannels() > configuration.getAudioChannelCount()) {
				channels = configuration.getAudioChannelCount();
			}

			if (!customFFmpegOptions.contains("-ac ") && channels > 0) {
				cmdList.add("-ac");
				cmdList.add(String.valueOf(channels));
			}

			if (!customFFmpegOptions.contains("-ab ")) {
				cmdList.add("-ab");
				if (renderer.isTranscodeToMPEGTSH264AAC()) {
					cmdList.add(Math.min(configuration.getAudioBitrate(), 320) + "k");
				} else {
					cmdList.add(String.valueOf(CodecUtil.getAC3Bitrate(configuration, params.aid)) + "k");
				}
			}
		}

		// Add the output options (-f, -c:a, -c:v, etc.)
		cmdList.addAll(getVideoTranscodeOptions(dlna, media, params));

		// Add custom options
		if (StringUtils.isNotEmpty(customFFmpegOptions)) {
			parseOptions(customFFmpegOptions, cmdList);
		}

		if (!dtsRemux) {
			cmdList.add("pipe:");
		}

		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);

		cmdArray = finalizeTranscoderArgs(
			filename,
			dlna,
			media,
			params,
			cmdArray
		);

		ProcessWrapperImpl pw = new ProcessWrapperImpl(cmdArray, params);

		setOutputParsing(dlna, pw, false);

		if (dtsRemux) {
			PipeProcess pipe;
			pipe = new PipeProcess(System.currentTimeMillis() + "tsmuxerout.ts");

			TsMuxeRVideo ts = new TsMuxeRVideo();
			File f = new File(configuration.getTempFolder(), "pms-tsmuxer.meta");
			String cmd[] = new String[]{ ts.executable(), f.getAbsolutePath(), pipe.getInputPipe() };
			pw = new ProcessWrapperImpl(cmd, params);

			PipeIPCProcess ffVideoPipe = new PipeIPCProcess(System.currentTimeMillis() + "ffmpegvideo", System.currentTimeMillis() + "videoout", false, true);

			cmdList.add(ffVideoPipe.getInputPipe());

			OutputParams ffparams = new OutputParams(configuration);
			ffparams.maxBufferSize = 1;
			ffparams.stdin = params.stdin;

			String[] cmdArrayDts = new String[cmdList.size()];
			cmdList.toArray(cmdArrayDts);

			cmdArrayDts = finalizeTranscoderArgs(
				filename,
				dlna,
				media,
				params,
				cmdArrayDts
			);

			ProcessWrapperImpl ffVideo = new ProcessWrapperImpl(cmdArrayDts, ffparams);

			ProcessWrapper ff_video_pipe_process = ffVideoPipe.getPipeProcess();
			pw.attachProcess(ff_video_pipe_process);
			ff_video_pipe_process.runInNewThread();
			ffVideoPipe.deleteLater();

			pw.attachProcess(ffVideo);
			ffVideo.runInNewThread();

			PipeIPCProcess ffAudioPipe = new PipeIPCProcess(System.currentTimeMillis() + "ffmpegaudio01", System.currentTimeMillis() + "audioout", false, true);
			StreamModifier sm = new StreamModifier();
			sm.setPcm(false);
			sm.setDtsEmbed(dtsRemux);
			sm.setSampleFrequency(48000);
			sm.setBitsPerSample(16);
			sm.setNbChannels(2);

			List<String> cmdListDTS = new ArrayList<>();
			cmdListDTS.add(executable());
			cmdListDTS.add("-y");
			cmdListDTS.add("-ss");

			if (params.timeseek > 0) {
				cmdListDTS.add(String.valueOf(params.timeseek));
			} else {
				cmdListDTS.add("0");
			}

			if (params.stdin == null) {
				cmdListDTS.add("-i");
			} else {
				cmdListDTS.add("-");
			}
			cmdListDTS.add(filename);

			if (params.timeseek > 0) {
				cmdListDTS.add("-copypriorss");
				cmdListDTS.add("0");
				cmdListDTS.add("-avoid_negative_ts");
				cmdListDTS.add("1");
			}

			cmdListDTS.add("-ac");
			cmdListDTS.add("2");

			cmdListDTS.add("-f");
			cmdListDTS.add("dts");

			cmdListDTS.add("-c:a");
			cmdListDTS.add("copy");

			cmdListDTS.add(ffAudioPipe.getInputPipe());

			String[] cmdArrayDTS = new String[cmdListDTS.size()];
			cmdListDTS.toArray(cmdArrayDTS);

			if (!params.mediaRenderer.isMuxDTSToMpeg()) { // No need to use the PCM trick when media renderer supports DTS
				ffAudioPipe.setModifier(sm);
			}

			OutputParams ffaudioparams = new OutputParams(configuration);
			ffaudioparams.maxBufferSize = 1;
			ffaudioparams.stdin = params.stdin;
			ProcessWrapperImpl ffAudio = new ProcessWrapperImpl(cmdArrayDTS, ffaudioparams);

			params.stdin = null;
			try (PrintWriter pwMux = new PrintWriter(f)) {
				pwMux.println("MUXOPT --no-pcr-on-video-pid --no-asyncio --new-audio-pes --vbr --vbv-len=500");
				String videoType = "V_MPEG-2";

				if (renderer.isTranscodeToMPEGTSH264AC3() || renderer.isTranscodeToMPEGTSH264AAC()) {
					videoType = "V_MPEG4/ISO/AVC";
				}

				if (params.no_videoencode && params.forceType != null) {
					videoType = params.forceType;
				}

				StringBuilder fps = new StringBuilder();
				fps.append("");
				if (params.forceFps != null) {
					fps.append("fps=").append(params.forceFps).append(", ");
				}

				String audioType = "A_AC3";
				if (dtsRemux) {
					if (params.mediaRenderer.isMuxDTSToMpeg()) {
						// Renderer can play proper DTS track
						audioType = "A_DTS";
					} else {
						// DTS padded in LPCM trick
						audioType = "A_LPCM";
					}
				}

				pwMux.println(videoType + ", \"" + ffVideoPipe.getOutputPipe() + "\", " + fps + "level=4.1, insertSEI, contSPS, track=1");
				pwMux.println(audioType + ", \"" + ffAudioPipe.getOutputPipe() + "\", track=2");
			}

			ProcessWrapper pipe_process = pipe.getPipeProcess();
			pw.attachProcess(pipe_process);
			pipe_process.runInNewThread();

			try {
				wait(50);
			} catch (InterruptedException e) {
			}

			pipe.deleteLater();
			params.input_pipes[0] = pipe;

			ProcessWrapper ff_pipe_process = ffAudioPipe.getPipeProcess();
			pw.attachProcess(ff_pipe_process);
			ff_pipe_process.runInNewThread();

			try {
				wait(50);
			} catch (InterruptedException e) {
			}

			ffAudioPipe.deleteLater();
			pw.attachProcess(ffAudio);
			ffAudio.runInNewThread();
		}

		pw.runInNewThread();
		return pw;
	}

	private JCheckBox multithreading;
	private JCheckBox videoRemuxTsMuxer;
	private JCheckBox fc;

	@Override
	public JComponent config() {
		return config("NetworkTab.5");
	}

	protected JComponent config(String languageLabel) {
		FormLayout layout = new FormLayout(
			"left:pref, 0:grow",
			"p, 3dlu, p, 3dlu, p, 3dlu, p"
		);
		PanelBuilder builder = new PanelBuilder(layout);
		builder.border(Borders.EMPTY);
		builder.opaque(false);

		CellConstraints cc = new CellConstraints();

		JComponent cmp = builder.addSeparator(Messages.getString(languageLabel), cc.xyw(2, 1, 1));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		multithreading = new JCheckBox(Messages.getString("MEncoderVideo.35"), configuration.isFfmpegMultithreading());
		multithreading.setContentAreaFilled(false);
		multithreading.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				configuration.setFfmpegMultithreading(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(multithreading, cc.xy(2, 3));

		videoRemuxTsMuxer = new JCheckBox(Messages.getString("MEncoderVideo.38"), configuration.isFFmpegMuxWithTsMuxerWhenCompatible());
		videoRemuxTsMuxer.setContentAreaFilled(false);
		videoRemuxTsMuxer.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				configuration.setFFmpegMuxWithTsMuxerWhenCompatible(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(videoRemuxTsMuxer, cc.xy(2, 5));

		fc = new JCheckBox(Messages.getString("MEncoderVideo.21"), configuration.isFFmpegFontConfig());
		fc.setContentAreaFilled(false);
		fc.setToolTipText(Messages.getString("FFmpeg.0"));
		fc.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				configuration.setFFmpegFontConfig(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(fc, cc.xy(2, 7));

		return builder.getPanel();
	}

	protected static List<String> parseOptions(String str) {
		return str == null ? null : parseOptions(str, new ArrayList<String>());
	}

	protected static List<String> parseOptions(String str, List<String> cmdList) {
		while (str.length() > 0) {
			if (str.charAt(0) == '\"') {
				int pos = str.indexOf('"', 1);
				if (pos == -1) {
					// No ", error
					break;
				}
				String tmp = str.substring(1, pos);
				cmdList.add(tmp.trim());
				str = str.substring(pos + 1);
			} else {
				// New arg, find space
				int pos = str.indexOf(' ');
				if (pos == -1) {
					// No space, we're done
					cmdList.add(str);
					break;
				}
				String tmp = str.substring(0, pos);
				cmdList.add(tmp.trim());
				str = str.substring(pos + 1);
			}
		}
		return cmdList;
	}

	/**
	 * Extracts embedded subtitles from video to file in SSA/ASS format, converts external SRT
	 * subtitles file to SSA/ASS format and applies fontconfig setting to that converted file
	 * and applies timeseeking when required.
	 *
	 * @param dlna DLNAResource
	 * @param media DLNAMediaInfo
	 * @param params Output parameters
	 * @param configuration
	 * @return Converted subtitle file
	 * @throws IOException
	 */
	public static File getSubtitles(DLNAResource dlna, DLNAMediaInfo media, OutputParams params, PmsConfiguration configuration) throws IOException {
		if (media == null || params.sid.getId() == -1 || !params.sid.getType().isText()) {
			return null;
		}

		String dir = configuration.getDataFile(SUB_DIR);
		File subsPath = new File(dir);
		if (!subsPath.exists()) {
			subsPath.mkdirs();
		}

		boolean applyFontConfig = configuration.isFFmpegFontConfig();
		boolean isEmbeddedSource = params.sid.getId() < 100;

		String filename = isEmbeddedSource ?
			dlna.getSystemName() : params.sid.getExternalFile().getAbsolutePath();

		String basename;

		long modId = new File(filename).lastModified();
		if (modId != 0) {
			// We have a real file
			basename = FilenameUtils.getBaseName(filename);
		} else {
			// It's something else, e.g. a url or psuedo-url without meaningful
			// lastmodified and (maybe) basename characteristics.
			basename = dlna.getName().replaceAll("[<>:\"\\\\/|?*+\\[\\]\n\r ']", "").trim();
			modId = filename.hashCode();
		}

		File convertedSubs;
		if (applyFontConfig || isEmbeddedSource) {
			convertedSubs = new File(subsPath.getAbsolutePath() + File.separator + basename + "_ID" + params.sid.getId() + "_" + modId + ".ass");
		} else {
			String tmp = params.sid.getExternalFile().getName().replaceAll("[<>:\"\\\\/|?*+\\[\\]\n\r ']", "").trim();
			convertedSubs = new File(subsPath.getAbsolutePath() + File.separator + modId + "_" + tmp);
		}

		if (convertedSubs.canRead()) {
			// subs are already converted
			if (applyFontConfig || isEmbeddedSource) {
				params.sid.setType(SubtitleType.ASS);
				params.sid.setExternalFileCharacterSet(CHARSET_UTF_8);
			}

			return convertedSubs;
		}

		boolean isExternalAss = false;
		if (
			params.sid.getType() == SubtitleType.ASS &&
			params.sid.isExternal() &&
			!isEmbeddedSource
		) {
			isExternalAss = true;
		}

		File tempSubs;
		if (
			isExternalAss ||
			(
				!applyFontConfig &&
				!isEmbeddedSource &&
				(params.sid.getType() == SubtitleType.SUBRIP || params.sid.getType() == SubtitleType.WEBVTT)
			)
		) {
			tempSubs = params.sid.getExternalFile();
		} else {
			tempSubs = convertSubsToAss(filename, media, params, configuration);
		}

		if (tempSubs == null) {
			return null;
		}

		if (!FileUtil.isFileUTF8(tempSubs)) {
			try {
				tempSubs = SubtitleUtils.applyCodepageConversion(tempSubs, convertedSubs);
				params.sid.setExternalFileCharacterSet(CHARSET_UTF_8);
			} catch (IOException ex) {
				params.sid.setExternalFileCharacterSet(null);
				LOGGER.warn("Exception during external file charset detection.", ex);
			}
		} else {
			FileUtils.copyFile(tempSubs, convertedSubs);
			tempSubs = convertedSubs;
		}

		// Now we're sure we actually have our own modifiable file
		if (
			applyFontConfig &&
			!(
				configuration.isUseEmbeddedSubtitlesStyle() &&
				params.sid.getType() == SubtitleType.ASS
			)
		) {
			try {
				tempSubs = applyFontconfigToASSTempSubsFile(tempSubs, media, configuration);
				params.sid.setExternalFileCharacterSet(CHARSET_UTF_8);
			} catch (IOException e) {
				LOGGER.debug("Applying subs setting ends with error: " + e);
				return null;
			}
		}

		if (isEmbeddedSource) {
			params.sid.setExternalFile(tempSubs);
			params.sid.setType(SubtitleType.ASS);
		}

		PMS.get().addTempFile(tempSubs, 30 * 24 * 3600 * 1000);
		return tempSubs;
	}

	/**
	 * Converts external subtitles file in SRT format or extract embedded subs to default SSA/ASS format
	 *
	 * @param fileName Subtitles file in SRT format or video file with embedded subs
	 * @param media
	 * @param params output parameters
	 * @param configuration
	 * @return Converted subtitles file in SSA/ASS format
	 */
	public static File convertSubsToAss(String fileName, DLNAMediaInfo media, OutputParams params, PmsConfiguration configuration) {
		if (!params.sid.getType().isText()) {
			return null;
		}
		List<String> cmdList = new ArrayList<>();
		File tempSubsFile;
		cmdList.add(configuration.getFfmpegPath());
		cmdList.add("-y");
		cmdList.add("-loglevel");
		if (LOGGER.isTraceEnabled()) { // Set -loglevel in accordance with LOGGER setting
			cmdList.add("info"); // Could be changed to "verbose" or "debug" if "info" level is not enough
		} else {
			cmdList.add("fatal");
		}

		// Try to specify input encoding if we have a non utf-8 external sub
		if (params.sid.getId() >= 100 && !params.sid.isExternalFileUtf8()) {
			String encoding = isNotBlank(configuration.getSubtitlesCodepage()) ?
					// Prefer the global user-specified encoding if we have one.
					// Note: likely wrong if the file isn't supplied by the user.
					configuration.getSubtitlesCodepage() :
				params.sid.getExternalFileCharacterSet() != null ?
					// Fall back on the actually detected encoding if we have it.
					// Note: accuracy isn't 100% guaranteed.
					params.sid.getExternalFileCharacterSet() :
				null; // Otherwise we're out of luck!
			if (encoding != null) {
				cmdList.add("-sub_charenc");
				cmdList.add(encoding);
			}
		}

		cmdList.add("-i");
		cmdList.add(fileName);

		if (params.sid.isEmbedded()) {
			cmdList.add("-map");
			cmdList.add("0:s:" + (media.getSubtitleTracksList().indexOf(params.sid)));
		}

		try {
			tempSubsFile = new File(configuration.getTempFolder(), FilenameUtils.getBaseName(fileName) + ".ass");
		} catch (IOException e1) {
			LOGGER.debug("Subtitles conversion finished wih error: " + e1);
			return null;
		}
		cmdList.add(tempSubsFile.getAbsolutePath());

		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);

		ProcessWrapperImpl pw = new ProcessWrapperImpl(cmdArray, params);
		pw.runInNewThread();

		try {
			pw.join(); // Wait until the conversion is finished
			pw.stopProcess(); // Avoid creating a pipe for this process and messing up with buffer progress bar
		} catch (InterruptedException e) {
			LOGGER.debug("Subtitles conversion finished wih error: " + e);
			return null;
		}

		tempSubsFile.deleteOnExit();
		return tempSubsFile;
	}

	public static File applyFontconfigToASSTempSubsFile(File tempSubs, DLNAMediaInfo media, PmsConfiguration configuration) throws IOException {
		LOGGER.debug("Applying fontconfig to subtitles " + tempSubs.getName());
		File outputSubs = tempSubs;
		StringBuilder outputString = new StringBuilder();
		File temp = new File(configuration.getTempFolder(), tempSubs.getName() + ".tmp");
		FileUtils.copyFile(tempSubs, temp);
		BufferedReader input = FileUtil.bufferedReaderWithCorrectCharset(temp);
		BufferedWriter output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputSubs), CHARSET_UTF_8));
		try {
			String line;
			String[] format = null;
			int i;
			boolean playResIsSet = false; // do not apply font size change when video resolution is set
			while ((line = input.readLine()) != null) {
				outputString.setLength(0);
				if (line.contains("[Script Info]")) {
					outputString.append(line).append("\n");
					output.write(outputString.toString());
					while ((line = input.readLine()) != null) {
						outputString.setLength(0);
						if (!line.isEmpty()) {
							if (line.contains("PlayResY:") || line.contains("PlayResX:")) {
								playResIsSet = true;
							}
							outputString.append(line).append("\n");
							output.write(outputString.toString());
						} else {
							if (!playResIsSet) {
								outputString.append("PlayResY: ").append(media.getHeight()).append("\n");
								outputString.append("PlayResX: ").append(media.getWidth()).append("\n");
							}
							break;
						}
					}
				}

				if (line != null && line.contains("Format:")) {
					format = line.split(",");
					outputString.append(line).append("\n");
					output.write(outputString.toString());
					continue;
				}

				if (line != null && line.contains("Style: Default")) {
					String[] params = line.split(",");

					for (i = 0; i < format.length; i++) {
						switch (format[i].trim()) {
							case "Fontname":
								if (!configuration.getFont().isEmpty()) {
									params[i] = configuration.getFont();
								}

								break;
							case "Fontsize":
								if (!playResIsSet) {
									params[i] = Integer.toString((int) ((Integer.parseInt(params[i]) * media.getHeight() / (double) 288 * Double.parseDouble(configuration.getAssScale()))));
								}

								break;
							case "PrimaryColour":
								String primaryColour = Integer.toHexString(configuration.getSubsColor());
								params[i] = "&H" + primaryColour.substring(6, 8) + primaryColour.substring(4, 6) + primaryColour.substring(2, 4);
								break;
							case "Outline":
								params[i] = configuration.getAssOutline();
								break;
							case "Shadow":
								params[i] = configuration.getAssShadow();
								break;
							case "MarginV":
								params[i] = configuration.getAssMargin();
								break;
							default:
								break;
						}
					}

					outputString.append(StringUtils.join(params, ",")).append("\n");
					output.write(outputString.toString());
					continue;
				}

				outputString.append(line).append("\n");
				output.write(outputString.toString());
			}
		} finally {
			input.close();
			output.flush();
			output.close();
			temp.deleteOnExit();
		}

		return outputSubs;
	}

	/**
	 * Collapse the multiple internal ways of saying "subtitles are disabled" into a single method
	 * which returns true if any of the following are true:
	 *
	 *     1) configuration.isDisableSubtitles()
	 *     2) params.sid == null
	 *     3) avisynth()
	 * @param params
	 * @return
	 */
	public boolean isDisableSubtitles(OutputParams params) {
		return configuration.isDisableSubtitles() || (params.sid == null) || avisynth();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return
	 */
	@Override
	public boolean isCompatible(DLNAResource resource) {
		if (
			PlayerUtil.isVideo(resource, Format.Identifier.MKV) ||
			PlayerUtil.isVideo(resource, Format.Identifier.MPG) ||
			"m3u8".equals(resource.getFormat().getMatchedExtension())
		) {
			return true;
		}

		return false;
	}

	// matches 'Duration: 00:17:17.00' but not 'Duration: N/A'
	static final Matcher reDuration = Pattern.compile("Duration:\\s+([\\d:.]+),").matcher("");

	/**
	 * Set up a filter to parse ffmpeg's stderr output for info
	 * (e.g. duration) if required.
	 */
	public void setOutputParsing(final DLNAResource dlna, ProcessWrapperImpl pw, boolean force) {
		if (configuration.isResumeEnabled() && dlna.getMedia() != null) {
			long duration = force ? 0 : (long) dlna.getMedia().getDurationInSeconds();
			if (duration == 0 || duration == DLNAMediaInfo.TRANS_SIZE) {
				OutputTextLogger ffParser = new OutputTextLogger(null, pw) {
					@Override
					public boolean filter(String line) {
						if (reDuration.reset(line).find()) {
							String d = reDuration.group(1);
							LOGGER.trace("[{}] setting duration: {}", ID, d);
							dlna.getMedia().setDuration(convertStringToTime(d));
							return false; // done, stop filtering
						}
						return true; // keep filtering
					}
				};
				ffParser.setFiltered(true);
				pw.setStderrConsumer(ffParser);
			}
		}
	}

	public static void deleteSubs() {
		FileUtils.deleteQuietly(new File(configuration.getDataFile(SUB_DIR)));
	}
}
