package com.hello1987.videoconverter;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.hello1987.videoconverter.mp4.InputSurface;
import com.hello1987.videoconverter.mp4.MP4Builder;
import com.hello1987.videoconverter.mp4.Mp4Movie;
import com.hello1987.videoconverter.mp4.OutputSurface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class VideoConverter {

    private static final String TAG = " ";

    private final static String MIME_TYPE = "video/avc";
    private final static int PROCESSOR_TYPE_OTHER = 0;
    private final static int PROCESSOR_TYPE_QCOM = 1;
    private final static int PROCESSOR_TYPE_INTEL = 2;
    private final static int PROCESSOR_TYPE_MTK = 3;
    private final static int PROCESSOR_TYPE_SEC = 4;
    private final static int PROCESSOR_TYPE_TI = 5;
    private static volatile VideoConverter instance = null;
    private final Object videoConvertSync = new Object();
    private Context context;
    private Handler handler;
    private SharedPreferences preferences;

    private ArrayList<VideoObject> videoConverterQueue = new ArrayList<VideoObject>();
    private boolean cancelCurrentVideoConversion = false;
    private boolean videoConverterFirstWrite = true;

    private Map<String, String> mPendingId = new HashMap<String, String>();

    public VideoConverter(Context context) {
        handler = new Handler(context.getMainLooper());
        preferences = context.getSharedPreferences("VideoConverter",
                Context.MODE_PRIVATE);
    }

    public static VideoConverter getInstance(Context context) {
        VideoConverter converter = instance;
        if (converter == null) {
            synchronized (VideoConverter.class) {
                converter = instance;
                if (converter == null) {
                    instance = converter = new VideoConverter(context);
                }
            }
        }
        return converter;
    }

    @TargetApi(16)
    public static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo lastCodecInfo = null;
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    lastCodecInfo = codecInfo;
                    if (!lastCodecInfo.getName().equals("OMX.SEC.avc.enc")) {
                        return lastCodecInfo;
                    } else if (lastCodecInfo.getName().equals(
                            "OMX.SEC.AVC.Encoder")) {
                        return lastCodecInfo;
                    }
                }
            }
        }
        return lastCodecInfo;
    }

    public void addPendingId(String key) {
        mPendingId.put(key, key);
    }

    public void removePendingId(String key) {
        mPendingId.remove(key);
    }

    public boolean isRequestPending(String key) {
        return mPendingId.containsValue(key);
    }

    public void scheduleVideoConverter(VideoObject videoObject,
                                       OnVideoConvertListener listener) {
        videoConverterQueue.add(videoObject);
        if (videoConverterQueue.size() == 1) {
            startVideoConverterFromQueue(listener);
        }
    }

    private void startVideoConverterFromQueue(OnVideoConvertListener listener) {
        if (!videoConverterQueue.isEmpty()) {
            synchronized (videoConvertSync) {
                cancelCurrentVideoConversion = false;
            }
            VideoObject videoObject = videoConverterQueue.get(0);
            VideoConvertRunnable.runConversion(context, videoObject, listener);
        }
    }

    private void checkConversionCanceled() throws Exception {
        boolean cancelConversion;
        synchronized (videoConvertSync) {
            cancelConversion = cancelCurrentVideoConversion;
        }
        if (cancelConversion) {
            throw new RuntimeException("canceled conversion");
        }
    }

    @TargetApi(16)
    private int selectTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    @TargetApi(16)
    private int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo
                .getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    private boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    @TargetApi(16)
    private long readAndWriteTrack(final VideoObject videoObject,
                                   MediaExtractor extractor, MP4Builder mediaMuxer,
                                   MediaCodec.BufferInfo info, long start, long end, File file,
                                   boolean isAudio, OnVideoConvertListener listener) throws Exception {
        int trackIndex = selectTrack(extractor, isAudio);
        if (trackIndex >= 0) {
            extractor.selectTrack(trackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(trackIndex);
            int muxerTrackIndex = mediaMuxer.addTrack(trackFormat, isAudio);
            int maxBufferSize = trackFormat
                    .getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            boolean inputDone = false;
            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
            long startTime = -1;

            checkConversionCanceled();

            while (!inputDone) {
                checkConversionCanceled();

                boolean eof = false;
                int index = extractor.getSampleTrackIndex();
                if (index == trackIndex) {
                    info.size = extractor.readSampleData(buffer, 0);

                    if (info.size < 0) {
                        info.size = 0;
                        eof = true;
                    } else {
                        info.presentationTimeUs = extractor.getSampleTime();
                        if (start > 0 && startTime == -1) {
                            startTime = info.presentationTimeUs;
                        }
                        if (end < 0 || info.presentationTimeUs < end) {
                            info.offset = 0;
                            info.flags = extractor.getSampleFlags();
                            if (mediaMuxer.writeSampleData(muxerTrackIndex,
                                    buffer, info, isAudio)) {
                                didWriteData(videoObject, file, false, false,
                                        listener);
                            }
                            extractor.advance();
                        } else {
                            eof = true;
                        }
                    }
                } else if (index == -1) {
                    eof = true;
                }
                if (eof) {
                    inputDone = true;
                }
            }

            extractor.unselectTrack(trackIndex);
            return startTime;
        }
        return -1;
    }

    @SuppressLint("NewApi")
    private boolean convertVideo(final VideoObject videoObject,
                                 OnVideoConvertListener listener) {
        String videoPath = videoObject.getVideoPath();
        long startTime = videoObject.getStartTime();
        long endTime = videoObject.getEndTime();
        int resultWidth = videoObject.getResultWidth();
        int resultHeight = videoObject.getResultHeight();
        int rotationValue = videoObject.getRotationValue();
        int originalWidth = videoObject.getOriginalWidth();
        int originalHeight = videoObject.getOriginalHeight();
        int bitrate = videoObject.getBitrate();
        int rotateRender = videoObject.getRotateRender();
        File cacheFile = new File(videoObject.getOutPath());

        if (cacheFile.exists()) {
            Log.i(TAG, "cacheFile exists!");
        } else {
            Log.i(TAG, "cacheFile not exists!");
        }

        if (Build.VERSION.SDK_INT < 18 && resultHeight > resultWidth
                && resultWidth != originalWidth
                && resultHeight != originalHeight) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
            rotationValue = 90;
            rotateRender = 270;
        } else if (Build.VERSION.SDK_INT > 20) {
            if (rotationValue == 90) {
                int temp = resultHeight;
                resultHeight = resultWidth;
                resultWidth = temp;
                rotationValue = 0;
                rotateRender = 270;
            } else if (rotationValue == 180) {
                rotateRender = 180;
                rotationValue = 0;
            } else if (rotationValue == 270) {
                int temp = resultHeight;
                resultHeight = resultWidth;
                resultWidth = temp;
                rotationValue = 0;
                rotateRender = 90;
            }
        }

        boolean isPreviousOk = preferences.getBoolean("isPreviousOk", true);
        preferences.edit().putBoolean("isPreviousOk", false).commit();

        File inputFile = new File(videoPath);
        if (inputFile.exists()) {
            Log.i(TAG, "video exists!");
        } else {
            Log.i(TAG, "video not exists!");
        }

        if (!inputFile.canRead() || !isPreviousOk) {
            didWriteData(videoObject, cacheFile, true, true, listener);
            preferences.edit().putBoolean("isPreviousOk", true).commit();
            return false;
        }

        videoConverterFirstWrite = true;
        boolean error = false;
        long videoStartTime = startTime;

        long time = System.currentTimeMillis();

        if (resultWidth != 0 && resultHeight != 0) {
            MP4Builder mediaMuxer = null;
            MediaExtractor extractor = null;

            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                Mp4Movie movie = new Mp4Movie();
                movie.setCacheFile(cacheFile);
                movie.setRotation(rotationValue);
                movie.setSize(resultWidth, resultHeight);
                mediaMuxer = new MP4Builder().createMovie(movie);
                extractor = new MediaExtractor();
                extractor.setDataSource(inputFile.toString());

                checkConversionCanceled();

                if (true) {
                    int videoIndex;
                    videoIndex = selectTrack(extractor, false);
                    if (videoIndex >= 0) {
                        MediaCodec decoder = null;
                        MediaCodec encoder = null;
                        InputSurface inputSurface = null;
                        OutputSurface outputSurface = null;

                        try {
                            long videoTime = -1;
                            boolean outputDone = false;
                            boolean inputDone = false;
                            boolean decoderDone = false;
                            int swapUV = 0;
                            int videoTrackIndex = -5;

                            int colorFormat;
                            int processorType = PROCESSOR_TYPE_OTHER;
                            String manufacturer = Build.MANUFACTURER
                                    .toLowerCase();
                            if (Build.VERSION.SDK_INT < 18) {
                                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                                colorFormat = selectColorFormat(codecInfo,
                                        MIME_TYPE);
                                if (colorFormat == 0) {
                                    throw new RuntimeException(
                                            "no supported color format");
                                }
                                String codecName = codecInfo.getName();
                                if (codecName.contains("OMX.qcom.")) {
                                    processorType = PROCESSOR_TYPE_QCOM;
                                    if (Build.VERSION.SDK_INT == 16) {
                                        if (manufacturer.equals("lge")
                                                || manufacturer.equals("nokia")) {
                                            swapUV = 1;
                                        }
                                    }
                                } else if (codecName.contains("OMX.Intel.")) {
                                    processorType = PROCESSOR_TYPE_INTEL;
                                } else if (codecName
                                        .equals("OMX.MTK.VIDEO.ENCODER.AVC")) {
                                    processorType = PROCESSOR_TYPE_MTK;
                                } else if (codecName
                                        .equals("OMX.SEC.AVC.Encoder")) {
                                    processorType = PROCESSOR_TYPE_SEC;
                                    swapUV = 1;
                                } else if (codecName
                                        .equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
                                    processorType = PROCESSOR_TYPE_TI;
                                }
                                Log.e(TAG, "codec = " + codecInfo.getName()
                                        + " manufacturer = " + manufacturer
                                        + "device = " + Build.MODEL);
                            } else {
                                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                            }
                            Log.e(TAG, "colorFormat = " + colorFormat);

                            int resultHeightAligned = resultHeight;
                            int padding = 0;
                            int bufferSize = resultWidth * resultHeight * 3 / 2;
                            if (processorType == PROCESSOR_TYPE_OTHER) {
                                if (resultHeight % 16 != 0) {
                                    resultHeightAligned += (16 - (resultHeight % 16));
                                    padding = resultWidth
                                            * (resultHeightAligned - resultHeight);
                                    bufferSize += padding * 5 / 4;
                                }
                            } else if (processorType == PROCESSOR_TYPE_QCOM) {
                                if (!manufacturer.toLowerCase().equals("lge")) {
                                    int uvoffset = (resultWidth * resultHeight + 2047)
                                            & ~2047;
                                    padding = uvoffset
                                            - (resultWidth * resultHeight);
                                    bufferSize += padding;
                                }
                            } else if (processorType == PROCESSOR_TYPE_TI) {
                                // resultHeightAligned = 368;
                                // bufferSize = resultWidth *
                                // resultHeightAligned * 3 / 2;
                                // resultHeightAligned += (16 - (resultHeight %
                                // 16));
                                // padding = resultWidth * (resultHeightAligned
                                // - resultHeight);
                                // bufferSize += padding * 5 / 4;
                            } else if (processorType == PROCESSOR_TYPE_MTK) {
                                if (manufacturer.equals("baidu")) {
                                    resultHeightAligned += (16 - (resultHeight % 16));
                                    padding = resultWidth
                                            * (resultHeightAligned - resultHeight);
                                    bufferSize += padding * 5 / 4;
                                }
                            }

                            extractor.selectTrack(videoIndex);
                            if (startTime > 0) {
                                extractor.seekTo(startTime,
                                        MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            } else {
                                extractor.seekTo(0,
                                        MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            }
                            MediaFormat inputFormat = extractor
                                    .getTrackFormat(videoIndex);
                            inputFormat
                                    .setInteger(
                                            MediaFormat.KEY_PROFILE,
                                            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                            inputFormat.setInteger("level",
                                    MediaCodecInfo.CodecProfileLevel.AVCLevel3);

                            MediaFormat outputFormat = MediaFormat
                                    .createVideoFormat(MIME_TYPE, resultWidth,
                                            resultHeight);
                            outputFormat.setInteger(
                                    MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                                    bitrate != 0 ? bitrate : 921600);
                            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE,
                                    25);
                            outputFormat.setInteger(
                                    MediaFormat.KEY_I_FRAME_INTERVAL, 10);
                            outputFormat
                                    .setInteger(
                                            MediaFormat.KEY_PROFILE,
                                            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                            outputFormat
                                    .setInteger(
                                            "level",
                                            MediaCodecInfo.CodecProfileLevel.AVCLevel13);
                            if (Build.VERSION.SDK_INT < 18) {
                                outputFormat.setInteger("stride",
                                        resultWidth + 32);
                                outputFormat.setInteger("slice-height",
                                        resultHeight);
                            }

                            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
                            encoder.configure(outputFormat, null, null,
                                    MediaCodec.CONFIGURE_FLAG_ENCODE);
                            if (Build.VERSION.SDK_INT >= 18) {
                                inputSurface = new InputSurface(
                                        encoder.createInputSurface());
                                inputSurface.makeCurrent();
                            }
                            encoder.start();

                            decoder = MediaCodec
                                    .createDecoderByType(inputFormat
                                            .getString(MediaFormat.KEY_MIME));
                            if (Build.VERSION.SDK_INT >= 18) {
                                outputSurface = new OutputSurface();
                            } else {
                                outputSurface = new OutputSurface(resultWidth,
                                        resultHeight, rotateRender);
                            }
                            decoder.configure(inputFormat,
                                    outputSurface.getSurface(), null, 0);
                            decoder.start();

                            final int TIMEOUT_USEC = 2500;
                            ByteBuffer[] decoderInputBuffers = null;
                            ByteBuffer[] encoderOutputBuffers = null;
                            ByteBuffer[] encoderInputBuffers = null;
                            if (Build.VERSION.SDK_INT < 21) {
                                decoderInputBuffers = decoder.getInputBuffers();
                                encoderOutputBuffers = encoder
                                        .getOutputBuffers();
                                if (Build.VERSION.SDK_INT < 18) {
                                    encoderInputBuffers = encoder
                                            .getInputBuffers();
                                }
                            }

                            checkConversionCanceled();

                            while (!outputDone) {
                                checkConversionCanceled();
                                if (!inputDone) {
                                    boolean eof = false;
                                    int index = extractor.getSampleTrackIndex();
                                    if (index == videoIndex) {
                                        int inputBufIndex = decoder
                                                .dequeueInputBuffer(TIMEOUT_USEC);
                                        if (inputBufIndex >= 0) {
                                            ByteBuffer inputBuf;
                                            if (Build.VERSION.SDK_INT < 21) {
                                                inputBuf = decoderInputBuffers[inputBufIndex];
                                            } else {
                                                inputBuf = decoder
                                                        .getInputBuffer(inputBufIndex);
                                            }
                                            int chunkSize = extractor
                                                    .readSampleData(inputBuf, 0);
                                            if (chunkSize < 0) {
                                                decoder.queueInputBuffer(
                                                        inputBufIndex,
                                                        0,
                                                        0,
                                                        0L,
                                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                                inputDone = true;
                                            } else {
                                                decoder.queueInputBuffer(
                                                        inputBufIndex,
                                                        0,
                                                        chunkSize,
                                                        extractor
                                                                .getSampleTime(),
                                                        0);
                                                extractor.advance();
                                            }
                                        }
                                    } else if (index == -1) {
                                        eof = true;
                                    }
                                    if (eof) {
                                        int inputBufIndex = decoder
                                                .dequeueInputBuffer(TIMEOUT_USEC);
                                        if (inputBufIndex >= 0) {
                                            decoder.queueInputBuffer(
                                                    inputBufIndex,
                                                    0,
                                                    0,
                                                    0L,
                                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                            inputDone = true;
                                        }
                                    }
                                }

                                boolean decoderOutputAvailable = !decoderDone;
                                boolean encoderOutputAvailable = true;
                                while (decoderOutputAvailable
                                        || encoderOutputAvailable) {
                                    checkConversionCanceled();
                                    int encoderStatus = encoder
                                            .dequeueOutputBuffer(info,
                                                    TIMEOUT_USEC);
                                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        encoderOutputAvailable = false;
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encoderOutputBuffers = encoder
                                                    .getOutputBuffers();
                                        }
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        MediaFormat newFormat = encoder
                                                .getOutputFormat();
                                        if (videoTrackIndex == -5) {
                                            videoTrackIndex = mediaMuxer
                                                    .addTrack(newFormat, false);
                                        }
                                    } else if (encoderStatus < 0) {
                                        throw new RuntimeException(
                                                "unexpected result from encoder.dequeueOutputBuffer: "
                                                        + encoderStatus);
                                    } else {
                                        ByteBuffer encodedData;
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encodedData = encoderOutputBuffers[encoderStatus];
                                        } else {
                                            encodedData = encoder
                                                    .getOutputBuffer(encoderStatus);
                                        }
                                        if (encodedData == null) {
                                            throw new RuntimeException(
                                                    "encoderOutputBuffer "
                                                            + encoderStatus
                                                            + " was null");
                                        }
                                        if (info.size > 1) {
                                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                                if (mediaMuxer.writeSampleData(
                                                        videoTrackIndex,
                                                        encodedData, info,
                                                        false)) {
                                                    didWriteData(videoObject,
                                                            cacheFile, false,
                                                            false, listener);
                                                }
                                            } else if (videoTrackIndex == -5) {
                                                byte[] csd = new byte[info.size];
                                                encodedData.limit(info.offset
                                                        + info.size);
                                                encodedData
                                                        .position(info.offset);
                                                encodedData.get(csd);
                                                ByteBuffer sps = null;
                                                ByteBuffer pps = null;
                                                for (int a = info.size - 1; a >= 0; a--) {
                                                    if (a > 3) {
                                                        if (csd[a] == 1
                                                                && csd[a - 1] == 0
                                                                && csd[a - 2] == 0
                                                                && csd[a - 3] == 0) {
                                                            sps = ByteBuffer
                                                                    .allocate(a - 3);
                                                            pps = ByteBuffer
                                                                    .allocate(info.size
                                                                            - (a - 3));
                                                            sps.put(csd, 0,
                                                                    a - 3)
                                                                    .position(0);
                                                            pps.put(csd,
                                                                    a - 3,
                                                                    info.size
                                                                            - (a - 3))
                                                                    .position(0);
                                                            break;
                                                        }
                                                    } else {
                                                        break;
                                                    }
                                                }

                                                MediaFormat newFormat = MediaFormat
                                                        .createVideoFormat(
                                                                MIME_TYPE,
                                                                resultWidth,
                                                                resultHeight);
                                                if (sps != null && pps != null) {
                                                    newFormat.setByteBuffer(
                                                            "csd-0", sps);
                                                    newFormat.setByteBuffer(
                                                            "csd-1", pps);
                                                }
                                                videoTrackIndex = mediaMuxer
                                                        .addTrack(newFormat,
                                                                false);
                                            }
                                        }
                                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                                        encoder.releaseOutputBuffer(
                                                encoderStatus, false);
                                    }
                                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        continue;
                                    }

                                    if (!decoderDone) {
                                        int decoderStatus = decoder
                                                .dequeueOutputBuffer(info,
                                                        TIMEOUT_USEC);
                                        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                            decoderOutputAvailable = false;
                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                            MediaFormat newFormat = decoder
                                                    .getOutputFormat();
                                            Log.e(TAG, "newFormat = "
                                                    + newFormat);
                                        } else if (decoderStatus < 0) {
                                            throw new RuntimeException(
                                                    "unexpected result from decoder.dequeueOutputBuffer: "
                                                            + decoderStatus);
                                        } else {
                                            boolean doRender;
                                            if (Build.VERSION.SDK_INT >= 18) {
                                                doRender = info.size != 0;
                                            } else {
                                                doRender = info.size != 0
                                                        || info.presentationTimeUs != 0;
                                            }
                                            if (endTime > 0
                                                    && info.presentationTimeUs >= endTime) {
                                                inputDone = true;
                                                decoderDone = true;
                                                doRender = false;
                                                info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                            }
                                            if (startTime > 0
                                                    && videoTime == -1) {
                                                if (info.presentationTimeUs < startTime) {
                                                    doRender = false;
                                                    Log.e(TAG,
                                                            "drop frame startTime = "
                                                                    + startTime
                                                                    + " present time = "
                                                                    + info.presentationTimeUs);
                                                } else {
                                                    videoTime = info.presentationTimeUs;
                                                }
                                            }
                                            decoder.releaseOutputBuffer(
                                                    decoderStatus, doRender);
                                            if (doRender) {
                                                boolean errorWait = false;
                                                try {
                                                    outputSurface
                                                            .awaitNewImage();
                                                } catch (Exception e) {
                                                    errorWait = true;
                                                    Log.e(TAG, e.getMessage());
                                                }
                                                if (!errorWait) {
                                                    if (Build.VERSION.SDK_INT >= 18) {
                                                        outputSurface
                                                                .drawImage(false);
                                                        inputSurface
                                                                .setPresentationTime(info.presentationTimeUs * 1000);
                                                        inputSurface
                                                                .swapBuffers();
                                                    } else {
                                                        Log.e(TAG,
                                                                "input buffer not available");
                                                    }
                                                }
                                            }
                                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                                decoderOutputAvailable = false;
                                                Log.e(TAG, "decoder stream end");
                                                if (Build.VERSION.SDK_INT >= 18) {
                                                    encoder.signalEndOfInputStream();
                                                } else {
                                                    int inputBufIndex = encoder
                                                            .dequeueInputBuffer(TIMEOUT_USEC);
                                                    if (inputBufIndex >= 0) {
                                                        encoder.queueInputBuffer(
                                                                inputBufIndex,
                                                                0,
                                                                1,
                                                                info.presentationTimeUs,
                                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (videoTime != -1) {
                                videoStartTime = videoTime;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                            error = true;
                        }

                        extractor.unselectTrack(videoIndex);

                        if (outputSurface != null) {
                            outputSurface.release();
                        }
                        if (inputSurface != null) {
                            inputSurface.release();
                        }
                        if (decoder != null) {
                            decoder.stop();
                            decoder.release();
                        }
                        if (encoder != null) {
                            encoder.stop();
                            encoder.release();
                        }

                        checkConversionCanceled();
                    }
                } else {
                    long videoTime = readAndWriteTrack(videoObject, extractor,
                            mediaMuxer, info, startTime, endTime, cacheFile,
                            false, listener);
                    if (videoTime != -1) {
                        videoStartTime = videoTime;
                    }
                }
                if (!error) {
                    readAndWriteTrack(videoObject, extractor, mediaMuxer, info,
                            videoStartTime, endTime, cacheFile, true, listener);
                }
            } catch (Exception e) {
                error = true;
                Log.e(TAG, e.getMessage());
            } finally {
                if (extractor != null) {
                    extractor.release();
                }
                if (mediaMuxer != null) {
                    try {
                        mediaMuxer.finishMovie(false);
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
                Log.e(TAG, "time = " + (System.currentTimeMillis() - time));
            }
        } else {
            preferences.edit().putBoolean("isPreviousOk", true).commit();
            didWriteData(videoObject, cacheFile, true, true, listener);
            return false;
        }
        preferences.edit().putBoolean("isPreviousOk", true).commit();
        didWriteData(videoObject, cacheFile, true, error, listener);
        return true;
    }

    private void didWriteData(final VideoObject videoObject, final File file,
                              final boolean last, final boolean error,
                              final OnVideoConvertListener listener) {
        final boolean firstWrite = videoConverterFirstWrite;
        if (firstWrite) {
            videoConverterFirstWrite = false;
        }

        runOnUIThread(new Runnable() {
            @Override
            public void run() {
                if (error) {
                    // failed callback
                    listener.onVideoConvertFailed(videoObject,
                            file.getAbsolutePath());
                } else {
                    if (firstWrite) {
                        // started callback
                        listener.onVideoConvertStarted(videoObject,
                                file.getAbsolutePath());
                    }

                    // completed callback
                    if (last) {
                        listener.onVideoConvertCompleted(videoObject, file
                                .getAbsolutePath(), last ? file.length() : 0);
                    }
                }
                if (error || last) {
                    synchronized (videoConvertSync) {
                        cancelCurrentVideoConversion = false;
                    }
                    videoConverterQueue.remove(videoObject);
                    startVideoConverterFromQueue(listener);
                }
            }
        });
    }

    private void runOnUIThread(Runnable runnable) {
        runOnUIThread(runnable, 0);
    }

    private void runOnUIThread(Runnable runnable, long delay) {
        if (delay == 0) {
            handler.post(runnable);
        } else {
            handler.postDelayed(runnable, delay);
        }
    }

    public interface OnVideoConvertListener {
        void onVideoConvertStarted(VideoObject videoObject, String outPath);

        void onVideoConvertFailed(VideoObject videoObject, String outPath);

        void onVideoConvertCompleted(VideoObject videoObject, String outPath,
                                     long fileLen);
    }

    private static class VideoConvertRunnable implements Runnable {
        private Context context;
        private VideoObject videoObject;
        private OnVideoConvertListener convertListener;

        private VideoConvertRunnable(Context context, VideoObject message,
                                     OnVideoConvertListener listener) {
            videoObject = message;
            convertListener = listener;
        }

        public static void runConversion(final Context context,
                                         final VideoObject obj, final OnVideoConvertListener listener) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        VideoConvertRunnable wrapper = new VideoConvertRunnable(
                                context, obj, listener);
                        Thread th = new Thread(wrapper, "VideoConvertRunnable");
                        th.start();
                        th.join();
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
            }).start();
        }

        @Override
        public void run() {
            VideoConverter.getInstance(context).convertVideo(videoObject,
                    convertListener);
        }
    }

}
