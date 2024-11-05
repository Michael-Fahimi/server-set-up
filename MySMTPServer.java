package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailWriter;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MySMTPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;
    private MailWriter mailWriter;
    private List<Mailbox> reciver;

    private static final String HELO = "HELO";
    private static final String EHLO = "EHLO";
    private static final String MAIL = "MAIL";
    private static final String RCPT = "RCPT";
    private static final String DATA = "DATA";
    private static final String RSET = "RSET";
    private static final String VRFY = "VRFY";
    private static final String NOOP = "NOOP";
    private static final String QUIT = "QUIT";

    private State state;
    private enum State { INIT, HELO, MAIL, RCPT, QUIT }

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's information.
     */
    public MySMTPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);


        this.state = State.INIT;

        this.reciver = new ArrayList<>();
    }

    /**
     * Handles the communication with an individual client. Must send the initial welcome message, and then repeatedly
     * read requests, process the individual operation, and return a response, according to the SMTP protocol. Empty
     * request lines should be ignored. Only returns if the connection is terminated or if the QUIT command is issued.
     * Must close the socket connection before returning.
     */
    @Override
    public void run() {
        try (this.socket) {
            answer("220 " + getHostName() + " SMTP Ready");

            String line;
            while ((line = socketIn.readLine()) != null) {

                if (line.isEmpty()) continue;

                handleRequest(line);

                if (this.state == State.QUIT) break;
            }

        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");

            e.printStackTrace();
        }
    }

    /**
     * Retrieves the name of the current host. Used in the response of commands like HELO and EHLO.
     *
     * @return A string corresponding to the name of the current host.
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        }
         catch (UnknownHostException e) {
            try (BufferedReader reader = Runtime.getRuntime().exec(new String[]{"hostname"}).inputReader()) {
                return reader.readLine();
            } 
            catch (IOException ex) {
                return "unknown_host";
            }
        }
    }



    private void handleEhloCommand(String arg) {
        if (state != State.INIT) {
            answer("503 Bad sequence of commands");
            return;
        }

        if (arg == null) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        state = State.HELO;
        answer("250 " + getHostName());
    }

    private void handleHeloCommand(String arg) {
        if (state != State.INIT) {
            answer("503 Bad sequence of commands");
            return;
        }

        if (arg == null) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        state = State.HELO;

        answer("250 " + getHostName());
    }

 
    private boolean formatt(String arg, String prefix) {
        String[] parsing = arg.split(":");

        return parsing.length > 1 && parsing[0].equalsIgnoreCase(prefix)  && parsing[1].charAt(0) == '<'   && parsing[1].charAt(parsing[1].length() - 1) == '>';
    }

    private void handleRcptCommand(String arg) {
        if (state != State.MAIL && state != State.RCPT) {
            answer("503 Bad sequence of commands");
            return;
        }

        if (arg == null || !arg.toLowerCase().startsWith("to:")) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        if (!formatt(arg, "TO")) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        String recipient = arg.substring(3).trim();
        recipient = recipient.substring(1, recipient.length() - 1);

        if (Mailbox.isValidUser(recipient)) {
            state = State.RCPT;
            reciver.add(new Mailbox(recipient));
            System.out.println(reciver.size());
            answer("250 OK");
        } 
        else   answer("550 No such user here");
        
    }

    private void handleMailCommand(String arg) {
        if (state != State.HELO) {
            answer("503 Bad sequence of commands");
            return;
        }

        if (arg == null) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        if (formatt(arg, "FROM")) {
            this.state = State.MAIL;
            answer("250 OK");
        }
         else  answer("501 Syntax error in parameters or arguments");

    }


    private void handleDataCommand() {
        if (state != State.RCPT) {
            answer("503 Bad sequence of commands");
            return;
        }

        answer("354 Start mail input");

        try {
            mailWriter = new MailWriter(reciver);
            StringBuilder strbild = new StringBuilder();
            String line;
            while (!(line = socketIn.readLine()).equals(".")) {
                strbild.append(line).append("\r\n");
            }
            char[] charbuf = strbild.toString().toCharArray();
            mailWriter.write(charbuf, 0, charbuf.length);
            mailWriter.flush();
            mailWriter.close();
            reciver = new ArrayList<>();

            state = State.HELO;
            answer("250 OK");
        } catch (IOException e) {
            answer("451 Requested action aborted: error in processing");
        }
    }

    private void handleRsetCommand() {

        mailWriter = null;
        this.reciver = new ArrayList<>();
        state = State.HELO;
        answer("250 OK");
    }

    private void handleVrfyCommand(String arg) {


        if (arg == null || !arg.contains("@")) {
            answer("501 Syntax error in parameters or arguments");
            return;
        }

        if (Mailbox.isValidUser(arg))  answer("250 OK");
        else   answer("550 No such user here");

    }

    private void handleNoopCommand() {
        answer("250 OK");
    }

    private void handleQuitCommand() {
        answer("221 " + getHostName() + " Service closing transmission channel");
        state = State.QUIT;
    }


    private void handleRequest(String request) {

        String[] parsing = request.split("\\s+", 2);
        String command = parsing[0].toUpperCase();
        String arg = parsing.length > 1 ? parsing[1] : null;

        try {
            switch (command) {
                case HELO:
                handleHeloCommand(arg);
                    break;
                case EHLO:
                handleEhloCommand(arg);
                    break;
                case MAIL:
                handleMailCommand(arg);
                    break;
                case RCPT:
                handleRcptCommand(arg);
                    break;
                case DATA:
                handleDataCommand();
                    break;
                case RSET:
                handleRsetCommand();
                    break;
                case VRFY:
                handleVrfyCommand(arg);
                    break;
                case NOOP:
                handleNoopCommand();
                    break;
                case QUIT:
                handleQuitCommand();
                    break;
                default:
                    answer("502 Command not implemented");
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void answer(String answer) {
        socketOut.println(answer + "\r");
    }




    /**
     * Main process for the SMTP server. Handles the argument parsing and creates a listening server socket. Repeatedly
     * accepts new connections from individual clients, creating a new server instance that handles communication with
     * that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException("This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);
            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MySMTPServer handler = new MySMTPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}