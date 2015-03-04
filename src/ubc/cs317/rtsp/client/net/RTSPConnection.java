/*
 * University of British Columbia Department of Computer Science CPSC317 -
 * Internet Programming Assignment 2
 * 
 * Author: Jonatan Schroeder January 2013
 * 
 * This code may not be used without written consent of the authors, except for
 * current and future projects and assignments of the CPSC317 course at UBC.
 */

package ubc.cs317.rtsp.client.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.PriorityBlockingQueue;

import ubc.cs317.rtsp.client.exception.RTSPException;
import ubc.cs317.rtsp.client.model.Frame;
import ubc.cs317.rtsp.client.model.Session;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

    private static final int BUFFER_LENGTH = 15000;
    private static final long MINIMUM_DELAY_READ_PACKETS_MS = 10;
    private static final long MINIMUM_PACKETS_TO_PLAY = 50;

    private static Session session;
    private Timer rtpTimer;

    // TODO Add additional fields, if necessary
    private InetAddress server;
    private static Socket RTSPSocket;
    private static BufferedWriter RTSPWriter;
    private static BufferedReader RTSPReader;

    private static DatagramSocket RTPPacket;
    private static PriorityBlockingQueue<Frame> queue = new PriorityBlockingQueue<Frame>(100);

    private static int cseq;
    private static String videoName;
    private static String sessionID;
    private Thread frameSender;

    private static int currentFrame = 0;
    private static int counter;
    private static long fps;
    private static long time;
    private static int frameNum = 0;
    private static int numOutOfOrder = 0;
    private static volatile boolean isClosed = false;
    private static volatile boolean isPaused = false;
    private static volatile boolean replay = false;
    private static volatile boolean isFull = false;
    
    private static int state;
    static final int INIT = 0;
    static final int READY = 1;
    static final int PLAYING = 2;

    /**
     * Establishes a new connection with an RTSP server. No message is sent at
     * this point, and no stream is set up.
     * 
     * @param session
     *            The Session object to be used for connectivity with the UI.
     * @param server
     *            The hostname or IP address of the server.
     * @param port
     *            The TCP port number where the server is listening to.
     * @throws RTSPException
     *             If the connection couldn't be accepted, such as if the host
     *             name or port number are invalid or there is no connectivity.
     */
    public RTSPConnection(Session session, String server, int port)
            throws RTSPException {
        System.out.println("Establishing RTSP connection");
        this.session = session;
        try {
            this.server = InetAddress.getByName(server);
            RTSPSocket = new Socket(this.server, port);
            RTSPWriter = new BufferedWriter(new OutputStreamWriter(
                    RTSPSocket.getOutputStream()));
            RTSPReader = new BufferedReader(new InputStreamReader(
                    RTSPSocket.getInputStream()));
        } catch (UnknownHostException e) {
        	throw new RTSPException(e);
        } catch (IOException e) {
            throw new RTSPException(e);
        }
        
        state = INIT;
        System.out.println("State: " + state);
        System.out.println("RTSP connection established\n");
    }

    /**
     * Sends a SETUP request to the server. This method is responsible for
     * sending the SETUP request, receiving the response and retrieving the
     * session identification to be used in future messages. It is also
     * responsible for establishing an RTP datagram socket to be used for data
     * transmission by the server. The datagram socket should be created with a
     * random UDP port number, and the port number used in that connection has
     * to be sent to the RTSP server for setup. This datagram socket should also
     * be defined to timeout after 1 second if no packet is received.
     * 
     * @param videoName
     *            The name of the video to be setup.
     * @throws RTSPException
     *             If there was an error sending or receiving the RTSP data, or
     *             if the RTP socket could not be created, or if the server did
     *             not return a successful response.
     */
    public synchronized void setup(String videoName) throws RTSPException {
        System.out.println("Sending SETUP request");
        this.videoName = videoName;
        if (state == INIT) {
            try {
                RTPPacket = new DatagramSocket();
                RTPPacket.setSoTimeout(1000);
                sendRTSPRequest("SETUP"); // Send SETUP request.
                RTSPResponse response = RTSPResponse
                        .readRTSPResponse(RTSPReader);
                printRTSPResponse(response);
                if (response.getResponseCode() == 200) {
                    state = READY;
                    sessionID = response.getHeaderValue("Session");
                    currentFrame = 0;
                } else {
                	handleRTSPException(response.getResponseCode());               	
                } 
            } catch (SocketException e) {
                throw new RTSPException("Connection could not be established.");
            } catch (IOException e) {
                throw new RTSPException("Connectivity error.");
            }
            System.out.println("State: " + state);
        } else {
            throw new RTSPException("Command not expected at this time.");
        }
        System.out.println("SETUP request sent\n");
    }

    /**
     * Sends a PLAY request to the server. This method is responsible for
     * sending the request, receiving the response and, in case of a successful
     * response, starting the RTP timer responsible for receiving RTP packets
     * with frames.
     * 
     * @throws RTSPException
     *             If there was an error sending or receiving the RTSP data, or
     *             if the server did not return a successful response.
     */
    public synchronized void play() throws RTSPException {
        System.out.println("Sending PLAY request");
        if (state == READY) {
            try {
                sendRTSPRequest("PLAY"); // Send PLAY request.
                RTSPResponse response = RTSPResponse
                        .readRTSPResponse(RTSPReader);
                printRTSPResponse(response);
                if (response.getResponseCode() == 200) {
                    state = PLAYING;
                    isClosed = false;
                    replay = false;
                    if (isPaused) {
                        rtpTimer.cancel();
                    }
                    isPaused = false;
                    startRTPTimer();
                    frameSender = new Thread(new FrameHandler());
                    frameSender.start();
                    time = System.currentTimeMillis();
                } else {
                	handleRTSPException(response.getResponseCode());               	
                } 
            } catch (IOException e) {
                frameSender.interrupt();
                throw new RTSPException(e);
            }
            System.out.println("State: " + state);
        } else if (state == PLAYING) {
        	replay = true;
        	teardown();
        	setup(videoName);
        	play();
        } else {
            throw new RTSPException("Command not expected at this time.");
        }
        System.out.println("PLAY request sent\n");
    }

    /**
     * Starts a timer that reads RTP packets repeatedly. The timer will wait at
     * least MINIMUM_DELAY_READ_PACKETS_MS after receiving a packet to read the
     * next one.
     */
    private void startRTPTimer() {
        rtpTimer = new Timer();
        rtpTimer.schedule(new TimerTask() {
            @Override
            public void run() {
//             	while (queue.size() == 80) {
//                		Frame frame = queue.poll();
//                		currentFrame = frame.getSequenceNumber();
//                		session.processReceivedFrame(frame);    
//                        System.out.println(queue.size());
//                		if (queue.size() <= 40) {
//                			break;
//                		}
//             		}
                		receiveRTPPacket(); 
            	}
      	}, 0, MINIMUM_DELAY_READ_PACKETS_MS);
    }

    /**
     * Receives a single RTP packet and processes the corresponding frame. The
     * data received from the datagram socket is assumed to be no larger than
     * BUFFER_LENGTH bytes. This data is then parsed into a Frame object (using
     * the parseRTPPacket method) and the method session.processReceivedFrame is
     * called with the resulting packet. In case of timeout no exception should
     * be thrown and no frame should be processed.
     */
    private void receiveRTPPacket() {
        byte[] packet = new byte[BUFFER_LENGTH];
        DatagramPacket RTPpacket = new DatagramPacket(packet, BUFFER_LENGTH);
        try {
        	RTPPacket.receive(RTPpacket);
            Frame frame = parseRTPPacket(RTPpacket.getData(),
                    RTPpacket.getLength());
            queue.put(frame);
            fps++;

        } catch (SocketTimeoutException e) {
            System.out.println("Timed out");
            if (isPaused) {
                System.out.println("Pausing");
                rtpTimer.cancel();
                frameSender.interrupt();
            } else {
                handleClosed();
            }
        } catch (IOException e) {
            System.out.println("IO Exception");
            if (!isPaused) {
                handleClosed();
            }
        }
    }

    private void handleClosed() {
        System.out.println("SERVER CLOSED !!!!!!!!!!!!!!!!!!!!!!!");
        isClosed = true;
        rtpTimer.cancel();
        RTPPacket.close();
        try {
            frameSender.interrupt();
            frameSender.join(10000);
            System.out.println("joined!");
        } catch (InterruptedException e1) {
            System.out.println("Join timedout");
            e1.printStackTrace();
        }
    }

    /**
     * Sends a PAUSE request to the server. This method is responsible for
     * sending the request, receiving the response and, in case of a successful
     * response, cancelling the RTP timer responsible for receiving RTP packets
     * with frames.
     * 
     * @throws RTSPException
     *             If there was an error sending or receiving the RTSP data, or
     *             if the server did not return a successful response.
     */
    public synchronized void pause() throws RTSPException {
        if (state == PLAYING) {
            try {
                sendRTSPRequest("PAUSE"); // Send PAUSE request.
                RTSPResponse response = RTSPResponse
                        .readRTSPResponse(RTSPReader);
                printRTSPResponse(response);
                if (response.getResponseCode() == 200) {
                    state = READY;
                    isPaused = true;
                    replay = false;
                    frameSender.interrupt();

                } else {
                	handleRTSPException(response.getResponseCode());               	
                } 
            } catch (IOException e) {
                throw new RTSPException("Connectivity error.");
            }
            System.out.println("State: " + state);
        } else {
            throw new RTSPException("Command not expected at this time.");
        }
    }

    /**
     * Sends a TEARDOWN request to the server. This method is responsible for
     * sending the request, receiving the response and, in case of a successful
     * response, closing the RTP socket. This method does not close the RTSP
     * connection, and a further SETUP in the same connection should be
     * accepted. Also this method can be called both for a paused and for a
     * playing stream, so the timer responsible for receiving RTP packets will
     * also be cancelled.
     * 
     * @throws RTSPException
     *             If there was an error sending or receiving the RTSP data, or
     *             if the server did not return a successful response.
     */
    public synchronized void teardown() throws RTSPException {
        if (state == READY || state == PLAYING) {
            try {
                sendRTSPRequest("TEARDOWN"); // Send TEARDOWN request.
                RTSPResponse response = RTSPResponse
                        .readRTSPResponse(RTSPReader);
                printRTSPResponse(response);
                if (response.getResponseCode() == 200) {
                    state = INIT;
                    isClosed = true;
                    rtpTimer.cancel();
                    queue.clear();
                    frameSender.interrupt();
                    try {
						frameSender.join(10000);
					} catch (InterruptedException e) {
						System.out.println("join timed out");
					}
                    RTPPacket.close();
                } else {
                	handleRTSPException(response.getResponseCode());               	
                } 
            } catch (IOException e) {
                throw new RTSPException("Connectivity error.");
            }
            System.out.println("State: " + state);
        } else {
            throw new RTSPException(
                    "Error in sending or receiving the RTSP data.");
        }
    }

    /**
     * Closes the connection with the RTSP server. This method should also close
     * any open resource associated to this connection, such as the RTP
     * connection, if it is still open.
     */
    public synchronized void closeConnection() {
        try {
            cseq = 0;
            if (RTPPacket != null) {
            	RTPPacket.close();
            }
            RTSPSocket.close();
            RTSPWriter.close();
            RTSPReader.close();
        } catch (IOException e) {
        }
    }

    /**
     * Parses an RTP packet into a Frame object.
     * 
     * @param packet
     *            the byte representation of a frame, corresponding to the RTP
     *            packet.
     * @return A Frame object.
     */
    private static Frame parseRTPPacket(byte[] packet, int length) {
        byte payloadType = (byte) (packet[1] & 0x7f);
        boolean marker = false;
        if (packet[1] >> 7 == 1) {
            marker = true;
        }
        short sequenceNumber = (short) (((packet[2] & 0xff) << 8) + (packet[3] & 0xff));
        int timestamp = packet[4] << 24 + packet[5] << 16 + packet[6] << 8 + packet[7];
        int offset = 12;
        return new Frame(payloadType, marker, sequenceNumber, timestamp,
                packet, offset, length - offset);
    }

    /**
     * Sends a request command to the RTSP server.
     * 
     * @param request
     *            the command we are to send.
     * @throws RTSPException
     */
    private static void sendRTSPRequest(String request) throws RTSPException {
        String requestString = null;
        cseq++;
        if (request.equals("SETUP")) {
            requestString = request + " " + videoName + " RTSP/1.0" + "\r\n"
                    + "CSeq: " + cseq + "\r\n"
                    + "Transport: RTP/UDP; client_port= "
                    + RTPPacket.getLocalPort() + "\r\n" + "\r\n";
        } else {
            requestString = request + " " + videoName + " RTSP/1.0" + "\r\n"
                    + "Cseq: " + cseq + "\r\n" + "Session: " + sessionID
                    + "\r\n" + "\r\n";
        }
        try {
            RTSPWriter.write(requestString);
            RTSPWriter.flush();
        } catch (IOException e) {
            throw new RTSPException(e);
        }
        System.out.println("client:\n" + requestString + "\n");
    }

    /**
     * Prints the response from the RTSP server
     * 
     * @param response
     *            the response string to be printed
     */
    private void printRTSPResponse(RTSPResponse response) {
        System.out.println("server:\n" + response.getRtspVersion() + " "
                + response.getResponseCode() + " "
                + response.getResponseMessage() + "\n" + "Cseq: "
                + response.getHeaderValue("cseq") + "\n" + "Session: "
                + response.getHeaderValue("session") + "\n");
    }

    /**
     * Prints the statistics from the video
     * 
     * @param time
     *            the time it took to play the video
     */
    private static void printStats(long time) {
        long frameRate = fps / time;
        //int framesOutOfOrder = (int) (numOutOfOrder / time);
        if (fps < 500) {
            long framesLost = 500 - fps;
            framesLost = framesLost / time;
            System.out.println("Number of frames lost is " + framesLost
                    + "/sec.");
        } else if (fps == 500) {
            System.out.println("Number of frames lost is 0/sec.");
        }
        System.out.println("The frame rate was " + frameRate + "/sec.");
//        System.out.println("The number of frames out of order was "
//                + framesOutOfOrder + "/sec.");
        System.out.println("Total frames was " + fps + " frames.");
        frameNum = 0;
        numOutOfOrder = 0;
        fps = 0;
    }
    
    /**
     * handle exceptions of RTSP protocol
     * 
     * @param responseCode
     *            RTSP's status code 
     */
    private static void handleRTSPException(int responseCode) throws RTSPException {
    	  if (responseCode == 404) {
             throw new RTSPException("Video Not Found.");
         } 
    	  if (responseCode == 408) {
         	throw new RTSPException("Request Timeout.");
         }
    	  if (responseCode == 454) {
           	throw new RTSPException("Session Not Found.");    		  
    	  }
    	  if (responseCode == 500) {
    		throw new RTSPException("Internal Server Error.");    		  
    	  }
    	
    }

    public static class FrameHandler implements Runnable {

        public void run() {
            init();

            while (true) {
                if (!queue.isEmpty() && !isPaused) {
                    System.out.println("Sending frame");
                    System.out.println(queue.size());
                    try {
                    	Frame frame = queue.peek();
                    	if (frame.getSequenceNumber() < currentFrame){
                    		queue.remove(frame);
                    	} else {
                    		frame = queue.poll();
                    		currentFrame = frame.getSequenceNumber();
                    		session.processReceivedFrame(frame);
                    	}
                    	if (queue.size() >= 80 && state == 2) {
                		sendRTSPRequest("PAUSE");
                		//need to check response
                		isFull = true;
                	}
                	while (isFull) {
                		frame = queue.poll();
                		currentFrame = frame.getSequenceNumber();
                		session.processReceivedFrame(frame);
                		if (queue.size() <= 40) {
                			isFull = false;
                			sendRTSPRequest("PLAY");
                    		//need to check response
                			break;
                		}                    		
                	}
                        Thread.sleep(40);
                    } catch (InterruptedException e1) {
                        System.out.println("Interrupted in sending");
                        if (isPaused || replay) {
                            return;
                        } else
                            continue;
                    } catch (RTSPException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
                } else if (!isClosed && queue.isEmpty() && !isPaused) {
                    int iterations = 0;
                    while ((queue.size() < 50 && iterations <= 50)) {
                        System.out.println("waiting 40ms");
                        iterations++;
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException e1) {
                            System.out.println("interrupted in waiting");
                            if (!isPaused || !replay) {
                                break;
                            } else
                                return;
                        }
                    }
                } else if (isClosed && queue.isEmpty()) {
                    System.out.println("done sending");
                    long currentTime = System.currentTimeMillis();
                	time = currentTime - time - MINIMUM_DELAY_READ_PACKETS_MS;
                	time = time/1000;
                	printStats(time);
                    break;
                } else if (isPaused) {
                    return;
                }
            }
        }

        private void init() {
            int iterations = 0;
            while (queue.size() < 50 && iterations <= 50) {
                iterations++;
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e1) {
                    break;
                }
            }
        }
    }
}
