/*
 * Copyright (C) 2019
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package rtsp.main;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import rtsp.client.RtspClient;
import rtsp.client.RtspVideoCallback;
import rtsp.decode.SampleFFmpegH264Decoder;

import java.nio.ByteBuffer;

public class JavaFxMain extends Application {

    private final static int width = 640;
    private final static int height = 480;
    private WritableImage wi = new WritableImage(1, 1);

    private String serverIpAddr = "localhost";
    private int rtspServerPort = 5540;

    @Override
    public void start(Stage primaryStage) {
        final StackPane rootPane = new StackPane();
        final Canvas canvas = new Canvas(width, height);
        rootPane.getChildren().add(canvas);
        final Scene scene = new Scene(rootPane, width, height);
        primaryStage.setScene(scene);

        final PixelFormat<ByteBuffer> pf = PixelFormat.getByteRgbInstance();
        final SampleFFmpegH264Decoder decoder = new SampleFFmpegH264Decoder();
        decoder.initialize();

        RtspVideoCallback videoCallback = (data, length, payloadType, sequenceNumber, timestamp, ssrc) -> {
            synchronized (canvas) {
                byte[] rgb = decoder.decode(data, length);
                if (rgb == null) return;
                int w = decoder.getWidth();
                int h = decoder.getHeight();
                if (wi.getWidth() != w || wi.getHeight() != h) wi = new WritableImage(w, h);
                wi.getPixelWriter().setPixels(0, 0, w, h, pf, rgb, 0, w * 3);
                canvas.getGraphicsContext2D().drawImage(wi, 0, 0);
            }
        };

        final RtspClient rtspClient = new RtspClient(serverIpAddr, rtspServerPort, videoCallback);
        rtspClient.start();

        primaryStage.showingProperty().addListener((obs, ov, nv) -> {
            if (ov && !nv) {
                rtspClient.stop();
                decoder.uninitialize();
            }
        });

        primaryStage.show();
    }

    private void run(String[] args) {
        for (String arg: args) {
            String[] split = arg.toLowerCase().split("=");
            if (split.length != 2) continue;
            try {
                if (split[0].startsWith("server")) {
                    serverIpAddr = split[1];
                } else if (split[0].startsWith("rtsp")) {
                    rtspServerPort = Integer.parseInt(split[1]);
                }
            } catch (Exception ignored) {
            }
        }
        launch(args);
    }

    public static void main(String[] args) {
        JavaFxMain javaFxMain = new JavaFxMain();
        javaFxMain.run(args);
    }

}
