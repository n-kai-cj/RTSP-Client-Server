/*
 * Copyright (C) 2019
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rtsp.decode;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

public class SampleFFmpegH264Decoder {

    private AVPacket avPacket = null;

    private AVCodec avCodec = null;

    private AVCodecContext avCodecContext = null;

    private AVFrame avFrameYuv = null;

    private BytePointer srcBp = null;

    private BytePointer yuvBp = null;

    private FFmpegPixelFormatConverter converter = null;

    private final static int avFrameFormat = avutil.AV_PIX_FMT_RGB24;

    private int width = -1;

    private int height = -1;


    public SampleFFmpegH264Decoder() {
    }


    public boolean initialize() {
        int ret;

        this.avPacket = new AVPacket();
        avcodec.av_init_packet(this.avPacket);

        this.avCodec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264);
        if (this.avCodec == null) {
            return false;
        }

        this.avCodecContext = avcodec.avcodec_alloc_context3(this.avCodec);
        if (this.avCodecContext == null) {
            return false;
        }
        this.avCodecContext.codec_type(avutil.AVMEDIA_TYPE_VIDEO);
        this.avCodecContext.pix_fmt(avutil.AV_PIX_FMT_YUV420P);

        ret = avcodec.avcodec_open2(this.avCodecContext, this.avCodec, (PointerPointer<Pointer>) null);
        if (ret < 0) {
            return false;
        }

        this.avFrameYuv = avutil.av_frame_alloc();
        if (this.avFrameYuv == null) {
            return false;
        }
        return true;
    }


    public void uninitialize() {
        if (this.avCodecContext != null) {
            avcodec.avcodec_close(this.avCodecContext);
            avcodec.avcodec_free_context(this.avCodecContext);
            this.avCodecContext = null;
        }
        if (this.avCodec != null) {
            this.avCodec = null;
        }
        if (this.avFrameYuv != null) {
            avutil.av_frame_free(this.avFrameYuv);
            this.avFrameYuv = null;
        }
        if (this.avPacket != null) {
            avcodec.av_packet_unref(this.avPacket);
            this.avPacket = null;
        }
    }


    public byte[] decode(byte[] data, int datalen) {
        if (this.srcBp == null || this.srcBp.asBuffer().remaining() < datalen) {
            this.srcBp = new BytePointer(avutil.av_malloc(datalen));
        }
        this.srcBp.put(data, 0, datalen);
        avcodec.av_init_packet(this.avPacket);
        avcodec.av_packet_from_data(this.avPacket, this.srcBp, datalen);

        int size = avcodec.avcodec_send_packet(this.avCodecContext, this.avPacket);
        if (size < 0) {
            return null;
        }
        int avPktSize = this.avPacket.size();

        avcodec.av_packet_unref(this.avPacket);
        size = avcodec.avcodec_receive_frame(this.avCodecContext, this.avFrameYuv);
        if (size < 0) {
            return null;
        }
        this.width = this.avFrameYuv.width();
        this.height = this.avFrameYuv.height();
        if (this.converter == null || this.converter.getWidth() != this.width || this.converter.getHeight() != this.height) {
            if (this.converter != null) this.converter.uninitialize();
            this.converter = new FFmpegPixelFormatConverter();
            this.converter.initialize(this.width, this.height, this.avFrameYuv.format(), avFrameFormat, 16);
        }
        int yuvLen = avutil.av_image_get_buffer_size(this.avFrameYuv.format(), this.width, this.height, 1);
        if (this.yuvBp == null || this.yuvBp.limit() < yuvLen) {
            this.yuvBp = new BytePointer(yuvLen);
        }
        int rgbLen = avutil.av_image_get_buffer_size(avFrameFormat, this.width, this.height, 1);

        size = avutil.av_image_copy_to_buffer(this.yuvBp, yuvLen,
                this.avFrameYuv.data(), this.avFrameYuv.linesize(),
                avutil.AV_PIX_FMT_YUV420P,
                this.width, this.height, 1);
        byte[] buffer = new byte[rgbLen];
        return this.converter.convert(this.width, this.height, this.yuvBp, buffer);
    }


    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }


}
