/*
 * University of British Columbia
 * Department of Computer Science
 * CPSC317 - Internet Programming
 * Assignment 2
 * 
 * Author: Jonatan Schroeder
 * January 2013
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
import java.util.Timer;
import java.util.TimerTask;

import ubc.cs317.rtsp.client.exception.RTSPException;
import ubc.cs317.rtsp.client.model.Frame;
import ubc.cs317.rtsp.client.model.Session;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

	private static final int BUFFER_LENGTH = 15000;
	private static final long MINIMUM_DELAY_READ_PACKETS_MS = 20;

	private Session session;
	private Timer rtpTimer;

	// TODO Add additional fields, if necessary
	private InetAddress server;
	private static Socket RTSPSocket;
	private static BufferedWriter RTSPWriter;
	private static BufferedReader RTSPReader;
	
	private static DatagramSocket RTPSocket;
	
	private int cseq;
	private String videoName;
	private String sessionID;

	
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
			RTSPWriter = new BufferedWriter(new OutputStreamWriter(RTSPSocket.getOutputStream()));
			RTSPReader = new BufferedReader(new InputStreamReader(RTSPSocket.getInputStream()));			
		}
		catch (IOException e){
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
		if (state == 0) {
			try {
				RTPSocket = new DatagramSocket();
				RTPSocket.setSoTimeout(1000);
				sendRTSPRequest("SETUP"); // Send SETUP request.
				RTSPResponse response = RTSPResponse.readRTSPResponse(RTSPReader);
				printRTSPResponse(response);
				if (response.getResponseCode() == 200) {
					state = READY;
					sessionID = response.getHeaderValue("Session");
				} else if (response.getResponseCode() == 404) {
					throw new RTSPException("Video not found.");
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
		if (state == 1) {
			try {
				sendRTSPRequest("PLAY"); // Send PLAY request.
				RTSPResponse response = RTSPResponse.readRTSPResponse(RTSPReader);
				printRTSPResponse(response);
				if (response.getResponseCode() == 200) {
					state = PLAYING;
					startRTPTimer();
				}
			} catch (IOException e) {
				throw new RTSPException(e);
			}
			System.out.println("State: " + state);
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
			RTPSocket.receive(RTPpacket);
			session.processReceivedFrame(parseRTPPacket(RTPpacket.getData(), RTPpacket.getLength()));
		} catch (IOException e) {
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
		if (state == 2) {
			try {
				sendRTSPRequest("PAUSE"); // Send PAUSE request.
				RTSPResponse response = RTSPResponse.readRTSPResponse(RTSPReader);
				printRTSPResponse(response);
				if (response.getResponseCode() == 200) {
					state = READY;
					rtpTimer.cancel();
				}
			} catch (IOException e) {
				throw new RTSPException("Connectivity error.");
			}
			System.out.println("State: " + state);
		} else {
			throw new RTSPException("Command not expected at this time.");
		}	}

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
		if (state == 1 || state == 2) {
			try {
				sendRTSPRequest("TEARDOWN"); // Send TEARDOWN request.
				RTSPResponse response = RTSPResponse.readRTSPResponse(RTSPReader);
				printRTSPResponse(response);
				if (response.getResponseCode() == 200) {
					state = INIT;
					rtpTimer.cancel();
					RTPSocket.close();
				}
			} catch (IOException e) {
				throw new RTSPException("Connectivity error.");
			}
			System.out.println("State: " + state);
		} else {
			throw new RTSPException("Command not expected at this time.");
		}	}

	/**
	 * Closes the connection with the RTSP server. This method should also close
	 * any open resource associated to this connection, such as the RTP
	 * connection, if it is still open.
	 */
	public synchronized void closeConnection() {
		try {
			cseq = 0;
			if (RTPSocket != null) {
				RTPSocket.close();
			}
			RTSPSocket.close();
			RTSPWriter.close();
			RTSPReader.close();
		} catch (IOException e) {
		}	}

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
		short sequenceNumber = (short) (packet[2] << 8 + packet[3]);
		int timestamp = packet[4] << 24 + packet[5] << 16 + packet[6] << 8 + packet[7];
		int offset = 12;
		return new Frame(payloadType, marker, sequenceNumber, timestamp, packet, offset, length - offset);
	}


private void sendRTSPRequest(String request) throws RTSPException {
	String requestString = null;
	cseq++;
	if (request.equals("SETUP")) {
		requestString = request + " " + videoName + " RTSP/1.0" + "\r\n"
				+ "CSeq: " + cseq + "\r\n"
				+ "Transport: RTP/UDP; client_port= " + RTPSocket.getLocalPort() + "\r\n"
				+ "\r\n";
	} else {
		requestString = request + " " + videoName + " RTSP/1.0" + "\r\n"
				+ "Cseq: " + cseq + "\r\n"
				+ "Session: " + sessionID + "\r\n"
				+ "\r\n";
	}
	try {
		RTSPWriter.write(requestString);
		RTSPWriter.flush();
	} catch (IOException e) {
		throw new RTSPException(e);
	}
	System.out.println("client:" + requestString + "\n");
}

private void printRTSPResponse(RTSPResponse response) {
	System.out.println("server:\n"
			+ response.getRtspVersion() + " "
			+ response.getResponseCode() + " "
			+ response.getResponseMessage() + "\n"
			+ "Cseq: " + response.getHeaderValue("cseq") + "\n"
			+ "Session: " + response.getHeaderValue("session") + "\n");
}
}
