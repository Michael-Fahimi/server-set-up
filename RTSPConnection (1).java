/*
 * Author: Jonatan Schroeder
 * Updated: October 2022
 *
 * This code may not be used without written consent of the authors.
 */

 package ca.yorku.rtsp.client.net;

 import ca.yorku.rtsp.client.exception.RTSPException;
 import ca.yorku.rtsp.client.model.Frame;
 import ca.yorku.rtsp.client.model.Session;
 
 import java.io.*;
 import java.net.*;
 import java.nio.ByteBuffer;
 import java.nio.ByteOrder;
 
 /**
  * This class represents a connection with an RTSP server.
  */
 public class RTSPConnection {
 
     private static final int BUFFER_LENGTH = 0x10000;
     private final Session session;
     
     
     
     protected int cilentseq = 0;
     private String sessionvalue;
     private Socket rtspSocket; 
     private DatagramSocket rtpSocket;
     private PrintWriter out; 
     private BufferedReader in;
     private String videoName;
 
 
     boolean threadRunning = false;
 
 
     private enum State {  INIT, READY, PLAYING }
 
     private State state = State.INIT;
 
 
 
     // TODO Add additional fields, if necessary
 
     /**
      * Establishes a new connection with an RTSP server. No message is
      * sent at this point, and no stream is set up.
      *
      * @param session The Session object to be used for connectivity with the UI.
      * @param server  The hostname or IP address of the server.
      * @param port    The TCP port number where the server is listening to.
      * @throws RTSPException If the connection couldn't be accepted,
      *                       such as if the host name or port number
      *                       are invalid or there is no connectivity.
      */
     public RTSPConnection(Session session, String server, int port) throws RTSPException {
 
         this.session = session;
 
         try {
             this.rtspSocket = new Socket(server, port);
             this.out = new PrintWriter(rtspSocket.getOutputStream(), true);
             this.in = new   BufferedReader(new InputStreamReader(rtspSocket.getInputStream()));
         }
 
 
         catch(Exception e) 
         {
             throw new RTSPException(e);
         }
     }
 
     /**
      * Sets up a new video stream with the server. This method is
      * responsible for sending the SETUP request, receiving the
      * response and retrieving the session identification to be used
      * in future messages. It is also responsible for establishing an
      * RTP datagram socket to be used for data transmission by the
      * server. The datagram socket should be created with a random
      * available UDP port number, and the port number used in that
      * connection has to be sent to the RTSP server for setup. This
      * datagram socket should also be defined to timeout after 2
      * seconds if no packet is received.
      *
      * @param videoName The name of the video to be setup.
      * @throws RTSPException If there was an error sending or
      *                       receiving the RTSP data, or if the RTP
      *                       socket could not be created, or if the
      *                       server did not return a successful
      *                       response.
      */
     public synchronized void setup(String videoName) throws RTSPException {
 
     
             try {
     
                 this.rtpSocket = new DatagramSocket();
                 rtpSocket.setSoTimeout(2000); 
                 int port = rtpSocket.getLocalPort();
     
                 this.videoName = videoName;
                 this.cilentseq++;
     
                 String req = "SETUP " + videoName + " RTSP/1.0\r\n" +
                         "CSeq: " + this.cilentseq + "\r\n" +
                         "Transport: RTP/UDP;client_port=" + port + "\r\n\r\n";
     
     
                 System.out.println(req);
                 out.print(req);
                 out.flush();
     
     
                 RTSPResponse response = readRTSPResponse();
     
                 if (response == null) throw new RTSPException("No response received from the server");
                 if (response.getResponseCode() != 200)   throw new RTSPException("The response was not successful");
     
     
                 String sessionvalue = response.getHeaderValue("Session");
     
     
                 if (sessionvalue != null && !sessionvalue.isEmpty())    this.sessionvalue = sessionvalue.split(";")[0].trim();
     
                 else throw new RTSPException("Session value null.");
     
     
                 System.out.println("\nResponse code: " + response.getResponseCode() + "\n" + cilentseq + "\n" + "Session value: " + sessionvalue);
     
                     state = State.READY;
     
     
             }
         catch (Exception e) {
             
             
             if (rtpSocket != null)    rtpSocket.close();
             
             
             
             throw new RTSPException("Setup failed: " + e.getMessage());
         }
 
     }
 
     /**
      * Starts (or resumes) the playback of a set up stream. This
      * method is responsible for sending the request, receiving the
      * response and, in case of a successful response, starting a
      * separate thread responsible for receiving RTP packets with
      * frames (achieved by calling start() on a new object of type
      * RTPReceivingThread).
      *
      * @throws RTSPException If there was an error sending or
      *                       receiving the RTSP data, or if the server
      *                       did not return a successful response.
      */
     public synchronized void play() throws RTSPException {
 
         try {
 
             this.cilentseq++;
 
             if (state != State.READY) {
                 throw new RTSPException("Cannot play in the state: " + state);
             }
 
 
             String play = "PLAY " + videoName + " RTSP/1.0\r\n" +   "CSeq: " + this.cilentseq + "\r\n" +    "Session: " + this.sessionvalue + "\r\n\r\n";
 
           
             
             out.print(play);
             out.flush();
 
             RTSPResponse response = readRTSPResponse();
             if (response == null)  throw new RTSPException("No response received from the server");
 
 
             if (response.getResponseCode() != 200)  throw new RTSPException("The response was not successful");
 
 
             state = State.PLAYING;
             new RTPReceivingThread().start();
 
         } catch (Exception e) {
             if (rtpSocket != null) {
                 rtpSocket.close();
             }
             throw new RTSPException("Play failed: " + e.getMessage());
         }
     }
 
     private class RTPReceivingThread extends Thread {
         /**
          * Continuously receives RTP packets until the thread is
          * cancelled or until an RTP packet is received with a
          * zero-length payload. Each packet received from the datagram
          * socket is assumed to be no larger than BUFFER_LENGTH
          * bytes. This data is then parsed into a Frame object (using
          * the parseRTPPacket() method) and the method
          * session.processReceivedFrame() is called with the resulting
          * packet. The receiving process should be configured to
          * timeout if no RTP packet is received after two seconds. If
          * a frame with zero-length payload is received, indicating
          * the end of the stream, the method session.videoEnded() is
          * called, and the thread is terminated.
          */
         @Override
         public void run() {
 
             threadRunning = true;
             byte[] buffer = new byte[BUFFER_LENGTH]; // Stores the RTP packet data
             DatagramPacket packets = new DatagramPacket(buffer, BUFFER_LENGTH);
 
             try {
                 while (threadRunning) {
                     rtpSocket.receive(packets);
 
                     if (packets.getLength() > 0) {
                         Frame frame = parseRTPPacket(packets);
                         
                         
                         if (frame != null && frame.getPayload().length == 0) 
                         {
                             session.videoEnded(cilentseq);
                             break;
                         }
 
 
                             session.processReceivedFrame(frame);
                     }
                 }
             } catch(Exception e) 
             {
                 
                 System.out.println("Error in run()");
             }
             finally 
             {
                 
                 threadRunning = false;
             }
 
 
         }
 
     }
 
     /**
      * Pauses the playback of a set up stream. This method is
      * responsible for sending the request, receiving the response
      * and, in case of a successful response, stopping the thread
      * responsible for receiving RTP packets with frames.
      *
      * @throws RTSPException If there was an error sending or
      *                       receiving the RTSP data, or if the server
      *                       did not return a successful response.
      */
     public synchronized void pause() throws RTSPException {
 
 
         try {
             this.cilentseq++;
 
             if (state != State.PLAYING) {
                 throw new RTSPException("Cannot pause in the state: " + state);
             }
 
             String pause = "PAUSE " + videoName + " RTSP/1.0\r\n" +  "CSeq: " + this.cilentseq + "\r\n" +   "Session: " + this.sessionvalue + "\r\n\r\n";
 
             out.print(pause);
             out.flush();
 
             RTSPResponse response = readRTSPResponse();
             
             if (response == null) {
                 throw new RTSPException("No response received from the server");
             }
 
             if (response.getResponseCode() != 200) {
                 throw new RTSPException("The response was not successful");
             }
 
             state = State.READY;
             threadRunning = false;
 
 
 
         } catch (Exception e) {
             throw new RTSPException("Pause failed: " + e.getMessage());
         }
     }
 
     /**
      * Terminates a set up stream. This method is responsible for
      * sending the request, receiving the response and, in case of a
      * successful response, closing the RTP socket. This method does
      * not close the RTSP connection, and a further SETUP in the same
      * connection should be accepted. Also, this method can be called
      * both for a paused and for a playing stream, so the thread
      * responsible for receiving RTP packets will also be cancelled,
      * if active.
      *
      * @throws RTSPException If there was an error sending or
      *                       receiving the RTSP data, or if the server
      *                       did not return a successful response.
      */
  // Tears down the RTSP session and associated resources.
     public synchronized void teardown() throws RTSPException {
 
         // Increment the client sequence number for the teardown request
         this.cilentseq++;
 
         try  { // Utilize a try-with-resources for out stream
 
             // Construct the TEARDOWN request message
             String teardownForwhat = "TEARDOWN " + videoName + " RTSP/1.0\r\n" +  "CSeq: " + this.cilentseq + "\r\n" + "Session: " + this.sessionvalue + "\r\n\r\n";
 
             // Send the TEARDOWN request
             out.print(teardownForwhat);
             out.flush();  // Flush the output stream
 
             // Read the RTSP response from the server
             RTSPResponse response = readRTSPResponse();
 
             // Check if the response is null (no response received)
             if (response == null) {
                 throw new RTSPException("No response received");
             }
 
             // Check if the response code is not 200 OK (unsuccessful teardown)
             if (response.getResponseCode() != 200) {
                 throw new RTSPException("Unsuccessful response");
             }
 
             // Update internal state after successful teardown
             state = State.INIT;
             threadRunning = false;
 
             // Close the RTP socket if it exists
             if (rtpSocket != null) {
                 rtpSocket.close();
             }
 
         } catch (Exception e) {
             // Wrap any exceptions in an RTSPException and re-throw
             throw new RTSPException("Teardown failed", e);  // Include the cause exception
         }
     }
 
 
     /**
      * Closes the connection with the RTSP server. This method should
      * also close any open resource associated to this connection,
      * such as the RTP connection and thread, if it is still open.
      */
  // Closes all network sockets and streams associated with the connection.
     public synchronized void closeConnection() {
 
         // Close the RTP socket, if it exists
         if (rtpSocket != null) {
             rtpSocket.close();  // Close the socket without any additional checks
         }
 
         // Close the output stream, if it exists
         if (out != null) {
             out.close();  // Close the output stream
         }
 
         // Close the input stream, handling potential IOExceptions
         if (in != null) {
             try {
                 in.close();  // Attempt to close the input stream
             } catch (IOException e) {  // Catch and ignore IOException during closure
                 // Ignore any IOExceptions during input stream closure
             }
         }
 
         // Close the RTSP socket, handling potential IOExceptions
         if (rtspSocket != null) {
             try {
                 rtspSocket.close();  // Attempt to close the RTSP socket
             } catch (IOException e) {  // Catch and ignore IOException during closure
                 // Ignore any IOExceptions during RTSP socket closure
             }
         }
     }
 
 
     /**
      * Parses an RTP packet into a Frame object. This method is
      * intended to be a helper method in this class, but it is made
      * public to facilitate testing.
      *
      * @param packet the byte representation of a frame, corresponding to the RTP packet.
      * @return A Frame object.
      */
     public static Frame parseRTPPacket(DatagramPacket packet) {
 
         byte[] dataArray = packet.getData();
         int length = packet.getLength();
 
         if (length < 12) {
             return null;
         }
 
         ByteBuffer insides = ByteBuffer.wrap(dataArray);
         
         byte secByte = insides.get(1); 
         byte payload1 = (byte) (secByte & 0b01111111);
         boolean marking = (secByte & 0b10000000) == 0b10000000;
 
         short seqNumber = (short) ((insides.get(2) << 8) | (insides.get(3) & 0b11111111));
         
         int timeStamp = ((insides.get(4) & 0b11111111) << 24) | ((insides.get(5) & 0b11111111) << 16) | ((insides.get(6) & 0b11111111) << 8) | (insides.get(7) & 0b11111111);
         
         byte[] payloadInside = new byte[length - 12];
 
         for (int i = 0; i < length - 12; i++) {
             payloadInside[i] = dataArray[i + 12];
         }
 
         return new Frame(payload1, marking, seqNumber, timeStamp, payloadInside);
 
     }
 
 
     /**
      * Reads and parses an RTSP response from the socket's input. This
      * method is intended to be a helper method in this class, but it
      * is made public to facilitate testing.
      *
      * @return An RTSPResponse object if the response was read
      * completely, or null if the end of the stream was reached.
      * @throws IOException   In case of an I/O error, such as loss of connectivity.
      * @throws RTSPException If the response doesn't match the expected format.
      */
     public RTSPResponse readRTSPResponse() throws IOException, RTSPException {
         
 
         try {
             String line = in.readLine();
 
  
             if (line == null)  return null;
 
 
   
             String[] parts = line.split(" ", 3);
             if (parts.length < 3)    throw new RTSPException("Incorrect response line");
 
 
 
             if (Integer.parseInt(parts[1]) == 455)   throw new RTSPException("bad request 400: " + line);
    
 
             RTSPResponse answer = new RTSPResponse(parts[0], Integer.parseInt(parts[1]), parts[2]);
             
             line = "";
             
 
             while (true) {
                 
                 line = in.readLine();
                 System.out.println(line);
                 if (line == null || line.isEmpty())    break; 
 
 
                 int colon_index = line.indexOf(":"); 
 
                 if (colon_index == -1)   throw new RTSPException("Wrong header line");
 
 
                 String headerName = line.substring(0, colon_index).trim();
                 String headerVal = line.substring(colon_index + 1).trim();
                 answer.addHeaderValue(headerName, headerVal);
                 
             }
 
             return answer; 
 
         } catch (IOException e)
         {
             throw new IOException("Error reading RTSP response: " + e.getMessage(), e);
         }
         
         
         catch (RTSPException e) 
         {
             throw new RTSPException("Error in RTSP response format: " + e.getMessage(), e);
         }
 
 
     }
 }