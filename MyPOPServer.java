package ca.yorku.eecs3214.mail.net;



import ca.yorku.eecs3214.mail.mailbox.MailMessage;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class MyPOPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;
    private boolean auth = false;
    private String user = null;
    private Mailbox mail = null;


    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's
     *                     information.
     */
    public MyPOPServer(Socket socket) throws IOException {


        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

    }


    /**
     * Handles the communication with an individual client. Must send the
     * initial welcome message, and then repeatedly read requests, process the
     * individual operation, and return a response, according to the POP3
     * protocol. Empty request lines should be ignored. Only returns if the
     * connection is terminated or if the QUIT command is issued. Must close the
     * socket connection before returning.
     */
    @Override
    public void run() {
        // Try-with-resources statement to ensure the socket is closed automatically
        try (this.socket) {
          // Send a welcome message to the client
          socketOut.println("+OK POP3 server ready\r");
      
          String line;
          // Loop to read commands from the client until a null line is received (indicating end of stream)
          while ((line = socketIn.readLine()) != null) {


            // Skip empty lines
            if (line.isEmpty()) continue;
            
      
            // Split the received line into arguments (command and parameters)
            String[] arg = line.split(" ");
      
            // Extract the first word (command) and convert it to uppercase for case-insensitive comparison
            String command = arg[0].toUpperCase();
      
            // Switch statement to handle different POP3 commands
            switch (command) {
              case "USER":
                // Call the handleUSER method to process the username
                handleUSER(arg);
                break;
              case "PASS":
                // Call the handlePASS method to process the password
                handlePASS(arg);
                break;
              case "STAT":
                // Call the handleSTAT method to send mailbox statistics
                handleSTAT();
                break;
              case "LIST":
                // Call the handleLIST method to send a list of emails
                handleLIST(arg);
                break;
              case "RETR":
                // Call the handleRETR method to retrieve an email
                handleRETR(arg);
                break;
              case "DELE":
                // Call the handleDELE method to delete an email
                handleDELE(arg);
                break;
              case "RSET":
                // Call the handleRSET method to reset the message deletion flag
                handleRSET();
                break;
              case "NOOP":
                // Call the handleNOOP method to do nothing (but send an OK response)
                handleNOOP();
                break;
              case "QUIT":
                // Call the handleQUIT method to terminate the connection
                handleQUIT();
                return; // Exit the loop after QUIT command
              default:
                // Send an error message for unknown commands
                socketOut.println("-ERR Unknown command\r");
            }
          }
        } catch (IOException e) {
          // Print error message and stack trace if an IOException occurs
          System.err.println("Error in client's connection handling.\r");
          e.printStackTrace();
        }
      }

      private void handleUSER(String[] arg) {
        // Check if the USER command has the correct number of arguments (username)
        if (arg.length != 2) {
          socketOut.println("-ERR Syntax error in USER command\r");
          return;
        }
      
        // Extract the username from the second argument
        user = arg[1];
      
        // Send a positive response indicating username accepted and password is required
        socketOut.println("+OK User name accepted, password required\r");
      }



    private void handlePASS(String[] arg) {
  // Check if the PASS command has the correct number of arg (password)
  if (arg.length != 2) {
    socketOut.println("-ERR Syntax error in PASS command\r");
    return;
  }

  // Check if the provided username is valid (presumably using a Mailbox class)
  if (Mailbox.isValidUser(user)) {
    // Create a Mailbox object for the current user
    mail = new Mailbox(user);

    try {
      // Attempt to load messages using the provided password
      mail.loadMessages(arg[1]);
      // Authentication successful
      auth = true;
      socketOut.println("+OK POP3 server ready\r");
    } catch (Mailbox.MailboxNotAuthenticatedException e) {
      // Password is invalid
      socketOut.println("-ERR invalid password\r");
    }
  } else {
    // Username not found
    socketOut.println("-ERR User name not found\r");
  }
}

private void handleSTAT() {
    // Check if the user is authenticated before processing the command
    if (!auth) {
      // Send an error message if not authenticated
      socketOut.println("-ERR Authenticate first\r");
      return;
    }
  
    // Get the number of messages in the mailbox (excluding deleted messages)
    int count = mail.size(false);  // Call to Mailbox class method
  
    // Get the total size of all undeleted messages in the mailbox
    long size = mail.getTotalUndeletedFileSize(false);  // Call to Mailbox class method
  
    // Construct a positive response with the number of messages and their total size
    socketOut.println("+OK " + count + " " + size + "\r");
  }



  private void handleLIST(String[] arg) {
    // Check if the user is authenticated
    if (!auth) {
      // Send an error message if not authenticated
      socketOut.println("-ERR Authenticate first\r");
      return;
    }
  
    // Handle different argument cases:
    if (arg.length == 1) { // LIST command without a message number
      // Send a positive response indicating the number of messages
      socketOut.println("+OK " + mail.size(false) + " messages:\r");
  
      // List information for each undeleted message
      for (int i = 1; i <= mail.size(true); i++) {


        MailMessage comm = mail.getMailMessage(i);
        if (!comm.isDeleted())  socketOut.println(i + " " + comm.getFileSize() + "\r");
        
      }
      socketOut.println(".\r"); // Termination marker
    } else { // LIST command with a specific message number
      int index = Integer.parseInt(arg[1]); // Extract message index
  
      // Check if the message index is valid
      if (index > mail.size(true) || index < 1) {
        socketOut.println("-ERR No such message\r");
        return;
      }
  
      MailMessage comm = mail.getMailMessage(index);
      // Check if the message is deleted
      if ( comm.isDeleted()) {
        socketOut.println("-ERR No such message\r");
        return;
      }
  
      // Send information for the specified message
      socketOut.println("+OK " + index + " " + comm.getFileSize() + "\r");
    }
  }


  

  private void handleRETR(String[] arg) {
    // Check if the user is authenticated
    if (!auth) {
      socketOut.println("-ERR Authenticate first\r");
      return;
    }
  
    // Extract the message index from the command arguments
    int index = Integer.parseInt(arg[1]);
  
    // Check for valid message index
    if (index > mail.size(true) || index < 1) {
      socketOut.println("-ERR No such message\r");
    } else {
      // Retrieve the MailMessage object for the specified index
      MailMessage comm = mail.getMailMessage(index);
  
      // Check if the message is deleted
      if (comm.isDeleted()) {
        socketOut.println("-ERR This message is deleted\r");
        return;
      }
  
      // Send a positive response with the message size
      socketOut.println("+OK " + comm.getFileSize() + " octets\r");
  
      // Read the message content from the file
      File content = comm.getFile();
      try (BufferedReader reader = new BufferedReader(new FileReader(content))) {
        String line;
        // Read the message content line by line and send it to the client
        while ((line = reader.readLine()) != null) {
          socketOut.println(line);
        }
        // Send termination marker
        socketOut.println(".\r");
      } catch (IOException e) {
        // Handle IOException and send an error message
        socketOut.println("-ERR Failed to read message content\r");
      }
    }
  }
  



private void handleDELE(String[] arg) {
  // Check if the user is authenticated
  if (!auth) {
    socketOut.println("-ERR Authenticate first\r");
    return;
  }

  // Extract the message index from the command arguments
  int index = Integer.parseInt(arg[1]);

  // Check for valid message index
  if (index > mail.size(true) || index < 1) {
    socketOut.println("-ERR No such message\r");
    return;
  }

  // Check if the message is already deleted
  if (mail.getMailMessage(index).isDeleted()) {
    socketOut.println("-ERR Message already deleted\r");
    return;
  }

  try {
    // Mark the message for deletion
    mail.getMailMessage(index).tagForDeletion();
    // Send a positive response
    socketOut.println("+OK Message deleted\r");
  } catch (Exception e) {
    // Catch any errors and send a generic error message
    socketOut.println("-ERR No such message\r");
  }
}


// Resets deletion marks for all messages in the mailbox.
public void handleRSET() {
    // Authentication check
    if (!auth) {
      // Send error message if not authenticated
      socketOut.println("-ERR Authenticate first\r");
      return;
    }
  
    // Undelete all messages
    for (int i = 1; i <= mail.size(true); i++) {
      mail.getMailMessage(i).undelete();
    }
  
    // Send positive response
    socketOut.println("+OK\r");
  }
  

// Does nothing but sends a positive response, often used for keeping connections alive.
public void handleNOOP() {
    // Simply send a positive OK response
    socketOut.println("+OK\r");
  }
  

// Terminates the POP3 session and closes the connection.
public void handleQUIT() {
    // Purge deleted messages
    if (this.mail != null) mail.deleteMessagesTaggedForDeletion();
    // Send positive response indicating server termination
    socketOut.println("+OK POP3 server signing off\r");
  }

    /**
     * Main process for the POP3 server. Handles the argument parsing and
     * creates a listening server socket. Repeatedly accepts new connections
     * from individual clients, creating a new server instance that handles
     * communication with that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or
     *                     accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException(
                    "This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);

            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            // noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MyPOPServer handler = new MyPOPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}
//last version of the code