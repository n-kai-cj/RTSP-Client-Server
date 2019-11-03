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

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.ByteBuffer;


public class FFmpegPixelFormatConverter {

    private SwsContext swsContext = null;

    private BytePointer pictureBuffer = null;

    private AVFrame avFrame = null;

    private AVFrame preFrame = null;

    private PointerPointer<AVFrame> preFramePP = null;

    private PointerPointer<AVFrame> avFramePP = null;

    private BytePointer srcBytePointer = null;

    private BytePointer dstBytePointer = null;

    private ByteBuffer dstBuffer = ByteBuffer.allocateDirect(1);

    private int src_pix_fmt = -1;

    private int dst_pix_fmt = -1;


    public FFmpegPixelFormatConverter() {
    }


    synchronized  public byte[] convert(int width, int height, byte[] src, byte[] dst) {
        if (this.srcBytePointer == null || this.srcBytePointer.asBuffer().remaining() != src.length) {
            this.srcBytePointer = new BytePointer(src.length);
        }
        this.srcBytePointer.put(src);
        return convert(width, height, this.srcBytePointer, dst);
    }


    synchronized public byte[] convert(int width, int height, BytePointer data, byte[] dstByte) {
        if (convertFrame(width, height, data) == null) {
            return null;
        }

        int len = avutil.av_image_get_buffer_size(this.dst_pix_fmt, width, height, 1);
        if (len < 0) {
            return null;
        }
        if (dstByte.length != len) {
            return null;
        }
        this.dstBuffer.clear();
        if (this.dstBuffer.remaining() != len) {
            this.dstBuffer = ByteBuffer.allocateDirect(len);
            this.dstBytePointer = new BytePointer(this.dstBuffer);
        }
        int ret = avutil.av_image_copy_to_buffer(this.dstBytePointer, len,
                this.avFrame.data(), this.avFrame.linesize(),
                this.dst_pix_fmt, width, height, 1);
        if (ret < 0) {
            return null;
        }
        this.dstBuffer.get(dstByte);
        return dstByte;
    }


    synchronized public AVFrame convertFrame(int width, int height, BytePointer data) {
        if (this.src_pix_fmt < 0 || this.dst_pix_fmt < 0) {
            return null;
        }
        int ret = avutil.av_image_fill_arrays(this.preFrame.data(), this.preFrame.linesize(), data,
                this.src_pix_fmt, width, height, 1);
        if (ret < 0) {
            return null;
        }
        ret = avutil.av_image_fill_arrays(this.avFrame.data(), this.avFrame.linesize(), this.pictureBuffer,
                this.dst_pix_fmt, width, height, 1);
        if (ret < 0) {
            return null;
        }

        ret = swscale.sws_scale(
                this.swsContext,
                this.preFramePP,
                this.preFrame.linesize(),
                0,
                height,
                this.avFramePP,
                this.avFrame.linesize());
        if (ret < 0) {
            return null;
        }
        return this.avFrame;
    }


    public boolean initialize(int width, int height, int src_pix_fmt, int dst_pix_fmt, int sws_scale_algorithm) {
        return initialize(width, height, src_pix_fmt, dst_pix_fmt, sws_scale_algorithm, null);
    }


    public boolean initialize(int width, int height, int src_pix_fmt, int dst_pix_fmt, int sws_scale_algorithm, AVFrame avFrame) {

        if (this.src_pix_fmt == src_pix_fmt && this.dst_pix_fmt == dst_pix_fmt) {
            return true;
        }
        this.avFrame = avFrame;
        if (this.avFrame == null) {

            this.avFrame = avutil.av_frame_alloc();
            if (this.avFrame == null) {
                return false;
            }
            this.avFrame.format(dst_pix_fmt);
            this.avFrame.width(width);
            this.avFrame.height(height);
            this.avFrame.pts(0);
        }
        this.preFrame = avutil.av_frame_alloc();
        if (this.preFrame == null) {
            return false;
        }
        this.preFrame.format(src_pix_fmt);
        this.preFrame.width(width);
        this.preFrame.height(height);
        this.preFrame.pts(0);
        if (avutil.av_frame_get_buffer(this.preFrame, (int) avutil.av_cpu_max_align()) < 0) {
            return false;
        }
        this.preFramePP = new PointerPointer<>(this.preFrame);
        this.avFramePP = new PointerPointer<>(this.avFrame);

        int pictureBufferSize = avutil.av_image_get_buffer_size(dst_pix_fmt, width, height, 1);
        if (pictureBufferSize <= 0) {
            return false;
        }
        this.pictureBuffer = new BytePointer(avutil.av_malloc(pictureBufferSize));

        this.swsContext = swscale.sws_getContext(
                width, height, src_pix_fmt,
                width, height, dst_pix_fmt,
                sws_scale_algorithm > 0 ? sws_scale_algorithm : swscale.SWS_BICUBIC, null, null, (double[]) null);
        if (this.swsContext == null) {
            return false;
        }
        this.src_pix_fmt = src_pix_fmt;
        this.dst_pix_fmt = dst_pix_fmt;
        return true;
    }


    public void uninitialize() {
        if (this.avFrame != null) {
            avutil.av_frame_free(this.avFrame);
            avutil.av_free(this.avFrame);
            this.avFrame = null;
        }
        if (this.preFrame != null) {
            avutil.av_frame_free(this.preFrame);
            avutil.av_free(this.preFrame);
            this.preFrame = null;
        }
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        if (this.pictureBuffer != null) {
            avutil.av_free(this.pictureBuffer);
            this.pictureBuffer = null;
        }
        this.src_pix_fmt = -1;
        this.dst_pix_fmt = -1;
    }

    public int getWidth() {
        if (this.avFrame == null) return -1;
        return this.avFrame.width();
    }

    public int getHeight() {
        if (this.avFrame == null) return -1;
        return this.avFrame.height();
    }

}
