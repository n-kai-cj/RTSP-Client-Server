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

package rtsp.client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class RtspClient implements Runnable {

    //RTP variables:
    //----------------
    private DatagramPacket rcvdp;            //UDP packet received from the server
    private DatagramSocket RTPsocket;        //socket to be used to send and receive UDP packets
    private int RTP_RCV_PORT = 25000; //port where the client will receive the RTP packets

    private Timer timer; //timer used to receive data from the UDP socket

    //RTSP variables
    //----------------
    //rtsp states
    private final static int INIT = 0;
    private final static int READY = 1;
    private final static int PLAYING = 2;
    private int state;            //RTSP state == INIT or READY or PLAYING
    private Socket RTSPsocket = null;           //socket used to send/receive RTSP messages
    private InetAddress ServerIPAddr;
    private String ServerHost;
    private int RTSP_server_port;

    //input and output stream filters
    private BufferedReader RTSPBufferedReader;
    private BufferedWriter RTSPBufferedWriter;
    private String VideoFileName; //video file to request to the server
    private int RTSPSeqNb = 0;           //Sequence number of RTSP messages within the session
    private String RTSPid;              // ID of the RTSP session (given by the RTSP Server)

    private final static String CRLF = "\r\n";

    //Thread:
    //------------------
    private Thread thread;
    private boolean loopFlag = true;

    //Video Callback:
    //------------------
    private RtspVideoCallback videoCallback;
    private ByteBuffer videoByteBuffer = ByteBuffer.allocate(10 * 1024 * 1024);
    private byte[] startCode = new byte[]{0, 0, 0, 1};


    public RtspClient(String serverIpAddr, int rtspServerPort, RtspVideoCallback videoCallback) {
        initialize(serverIpAddr, rtspServerPort, videoCallback);
    }

    private void initialize(String serverIpAddr, int rtspServerPort, RtspVideoCallback _videoCallback) {
        ServerHost = serverIpAddr;
        RTSP_server_port = rtspServerPort;
        videoCallback = _videoCallback;
    }

    public void start() {
        loopFlag = true;
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        loopFlag = false;
        try {
            if (RTSPBufferedWriter != null) {
                RTSPBufferedWriter.close();
                RTSPBufferedWriter = null;
            }
            if (RTSPBufferedReader != null) {
                RTSPBufferedReader.close();
                RTSPBufferedReader = null;
            }
            if (RTSPsocket != null) {
                RTSPsocket.close();
                RTSPsocket = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        //init timer
        //--------------------------
        timer = new Timer();

        //allocate enough memory for the buffer used to receive data from the server
        byte[] buf = new byte[1500];

        //Construct a DatagramPacket to receive data from the UDP socket
        rcvdp = new DatagramPacket(buf, buf.length);

        VideoFileName = String.format("rtsp://%s:%d/dumvideo.mp4/trackID=3", ServerHost, RTSP_server_port);
        try {
            ServerIPAddr = InetAddress.getByName(ServerHost);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        //Establish a TCP connection with the server to exchange RTSP messages
        //------------------
        while (loopFlag && RTSPsocket == null) {
            try {
                RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);
            } catch (IOException e) {
                e.printStackTrace();
                RTSPsocket = null;
                sleep(100);
                continue;
            }

            //Set input and output stream filters:
            try {
                RTSPBufferedReader = new BufferedReader(new InputStreamReader(RTSPsocket.getInputStream()));
                RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(RTSPsocket.getOutputStream()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //init RTSP sequence number
        RTSPSeqNb = 0;

        //init RTSP state:
        state = INIT;

        // start rtsp client
        if (!describe()) {
            System.out.println("describe error");
            return;
        }
        if (!setup()) {
            System.out.println("setup error");
            return;
        }
        play();

        while (loopFlag) {
            sleep(1000);
        }

        pause();
        teardown();

        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private boolean describe() {

        //increase RTSP sequence number
        RTSPSeqNb++;

        //Send DESCRIBE message to the server
        sendRequest("DESCRIBE");

        //Wait for the response
        if (parseServerResponse() != 200) {
            System.out.println("Invalid Server Response");
            return false;
        }
        return true;
    }

    private boolean setup() {

        if (state == INIT) {
            //Init non-blocking RTPsocket that will be used to receive data
            try {
                //construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
                RTPsocket = new DatagramSocket();
                RTP_RCV_PORT = RTPsocket.getLocalPort();
                RTPsocket.setReceiveBufferSize(10 * 1024 * 1024);
                //set TimeOut value of the socket to 5msec.
                RTPsocket.setSoTimeout(5);
            } catch (SocketException se) {
                System.out.println("Socket exception: " + se);
                System.exit(0);
            }

            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send SETUP message to the server
            sendRequest("SETUP");

            //Wait for the response
            if (parseServerResponse() != 200) {
                System.out.println("Invalid Server Response");
                return false;
            }
            //change RTSP state and print new state
            state = READY;

            return true;
        }
        //else if state != INIT then do nothing
        return true;
    }

    private void play() {

        if (state == READY) {
            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send PLAY message to the server
            sendRequest("PLAY");

            //Wait for the response
            if (parseServerResponse() != 200) {
                System.out.println("Invalid Server Response");
                return;
            }
            //change RTSP state and print out new state
            state = PLAYING;

            //start the timer
            timer.schedule(new timerListener(), 0, 1);
        }
        //else if state != READY then do nothing
    }

    private void pause() {

        if (state == PLAYING) {
            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send PAUSE message to the server
            sendRequest("PAUSE");

            //Wait for the response
            if (parseServerResponse() != 200) {
                System.out.println("Invalid Server Response");
                return;
            }
            //change RTSP state and print out new state
            state = READY;

            //stop the timer
            timer.cancel();
        }
        //else if state != PLAYING then do nothing
    }

    private void teardown() {

        //increase RTSP sequence number
        RTSPSeqNb++;

        //Send TEARDOWN message to the server
        sendRequest("TEARDOWN");

        //Wait for the response
        if (parseServerResponse() != 200) {
            System.out.println("Invalid Server Response");
            return;
        }
        //change RTSP state and print out new state
        state = INIT;

        //stop the timer
        timer.cancel();

    }

    //------------------------------------
    //Handler for timer
    //------------------------------------
    class timerListener extends TimerTask {

        @Override
        public void run() {

            try {
                //receive the DP from the socket, save time for stats
                RTPsocket.receive(rcvdp);

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
                int pt = rtp_packet.getpayloadtype();
                int seqNb = rtp_packet.getsequencenumber();
                int timestamp = rtp_packet.gettimestamp();

                // print debug info
                //byte[] b = rcvdp.getData();
                //System.out.println(String.format("H:size=%4d ts=%d| %02x %02x| seq=%02x %02x| ts=%02x %02x %02x %02x| ssrc=%02x %02x %02x %02x| %02x %02x %02x %02x|", rtp_packet.getpayload_length(), timestamp, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]));

                //print header bitstream:
                //rtp_packet.printheader();

                //get the payload bitstream from the RTPpacket object
                int payload_length = rtp_packet.getpayload_length();
                byte[] payload = new byte[payload_length];
                rtp_packet.getpayload(payload);

                // fragmentation unit indicator
                if ((payload[0] & 0x80) == 0 && ((payload[0] & 0x1f) == 28 || (payload[0] & 0x1f) == 29)) {

                    // start bit
                    if ((payload[1] & 0x80) == 0x80) {
                        byte NRI = (byte) (payload[0] & 0x60);
                        byte nalType = (byte) (payload[1] & 0x1f);
                        byte nalu = (byte) (NRI + nalType);

                        videoByteBuffer.put(startCode, 0, startCode.length);
                        videoByteBuffer.put(nalu);
                    }

                    videoByteBuffer.put(payload, 2, payload_length - 2);

                    // end bit
                    if ((payload[1] & 0x40) == 0x40) {
                        // video callback
                        videoCallback(pt, seqNb, timestamp, rtp_packet.Ssrc);
                    }
                } else {
                    // non fragmentation
                    videoByteBuffer.put(startCode, 0, startCode.length);
                    videoByteBuffer.put(payload, 0, payload_length);

                    if ((payload[0] & 0x1f) == 1 || (payload[0] & 0x1f) == 2 || (payload[0] & 0x1f) == 3 || (payload[0] & 0x1f) == 4 || (payload[0] & 0x1f) == 5) {
                        // video callback
                        videoCallback(pt, seqNb, timestamp, rtp_packet.Ssrc);
                    }
                }

            } catch (InterruptedIOException iioe) {
                //System.out.println("Nothing to read");
            } catch (IOException ioe) {
                System.out.println("Exception caught: " + ioe);
            }
        }

        private void videoCallback(int payloadType, int sequenceNumber, int timestamp, int ssrc) {
            if (videoCallback == null) return;
            int esSize = videoByteBuffer.position();
            byte[] esData = new byte[esSize];
            videoByteBuffer.flip();
            videoByteBuffer.get(esData);
            videoByteBuffer.clear();
            videoCallback.callback(esData, esData.length, payloadType, sequenceNumber, timestamp, ssrc);
        }

    }

    //------------------------------------
    //Parse Server Response
    //------------------------------------
    private int parseServerResponse() {
        int reply_code = 0;
        String StatusLine;
        try {
            do {
                StatusLine = RTSPBufferedReader.readLine();
                //System.out.println("RTSP Client - Received from Server:");
                //System.out.println(StatusLine);

                String[] split = StatusLine.split(" ");
                for (int i = 0; i < split.length; i++) {
                    if (split[i].startsWith("RTSP/") && i + 1 < split.length) {
                        reply_code = Integer.parseInt(split[i + 1]);
                    }
                    //if state == INIT gets the Session Id from the SessionLine
                    if (state == INIT && split[i].equals("Session:") && i + 1 < split.length) {
                        RTSPid = split[i + 1];
                    }
                }
            } while (StatusLine.length() > 0);

        } catch (IOException e) {
            e.printStackTrace();
        }
        return reply_code;
    }

    //------------------------------------
    //Send RTSP Request
    //------------------------------------

    private void sendRequest(String request_type) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket

            //write the request line:
            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);

            //write the CSeq line:
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            //check if request_type is equal to "SETUP" and in this case write the
            //Transport: line advertising to the server the port used to receive
            //the RTP packets RTP_RCV_PORT
            if (request_type.equals("SETUP")) {
                RTSPBufferedWriter.write("Transport: RTP/UDP;unicast;client_port=" + RTP_RCV_PORT + "-" + (RTP_RCV_PORT + 1) + CRLF);
            } else if (request_type.equals("DESCRIBE")) {
                RTSPBufferedWriter.write("Accept: application/sdp" + CRLF);
            } else {
                //otherwise, write the Session line from the RTSPid field
                RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            }

            // add final newline
            RTSPBufferedWriter.write(CRLF);

            RTSPBufferedWriter.flush();

        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

}
