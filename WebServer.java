import java.io.*;
import java.net.*;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public class WebServer{
    static volatile Boolean running = true;
    public static void main(String[] args) {
        try {

        Thread https = null,http;
        
            //check length of args and start HTTPS to handle HTTPS connections
        if(args.length == 2){
            System.out.println("Starting HTTPS Server");
            HttpsServer serveHTTPS= new HttpsServer(args[0], args[1]);
            https = new Thread(serveHTTPS);
            https.start();
            
        }else if(args.length == 1 || args.length > 2){
            System.err.println("USAGE: java WebServer.java <keystore.jks> <keyStorePassword>");
        }
            //start thread to handle HTTP connection
        System.out.println("Starting HTTP Server");
        HTTPServer serve= new HTTPServer();
        http = new Thread(serve);
        http.start();

            //clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nCtrl+C detected. Stopping threads...");
            running = false; // This will break the main loop
        }));
            
            //wait for HTTP and HTTPS thread
        if(args.length == 2)
            https.join();

        http.join();

        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }

        
    }
    static class HTTPServer implements Runnable{
        @Override
        public void run(){
                // Set the port number
            int port = 80;
            
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("HTTP Server listening on port " + port);
                
                    // Infinite loop till ctrl+c to listen for incoming connections
                while (running) {
                    Socket clientSocket = serverSocket.accept();

                        // Create a new thread to handle the request
                    HttpRequestHandler requestHandler = new HttpRequestHandler(clientSocket);
                    Thread thread = new Thread(requestHandler);
                    thread.start();
                }
            } catch (IOException e) {
                System.err.println("Server exception: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    static class HttpsServer implements Runnable {

        String keyStore;
        String keyStorePassword;

        public HttpsServer(String keyStore, String keyStorePassword){
            this.keyStore = keyStore;
            this.keyStorePassword = keyStorePassword;
        }

        @Override
        public void run(){
            int port = 443;
            
                //setting stored key path and password provided
            try {
                System.out.println("Reading keyStore and password");
                System.setProperty("javax.net.ssl.keyStore", keyStore);
                System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
            } catch (Exception e) {
                System.out.println("Error with finding keyStore or password");
                System.err.println("Request handling exception: " + e.getMessage());
                e.printStackTrace();
                return;
            }

            try{
                //creating SSL socket for connection on port 443
            SSLServerSocketFactory sslServerSocketFactory = 
            (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket sslServerSocket = 
            (SSLServerSocket) sslServerSocketFactory.createServerSocket(port);

            System.out.println("HTTPS Server listening on port " + port);

                //accept new HTTPS connection and create new thread
            while (running) { 
                SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept();

                HttpsRequestHandler requestHandler = new HttpsRequestHandler(sslSocket);
                Thread thread = new Thread(requestHandler);
                thread.start();
            }
            }catch(IOException e){
                System.err.println("Request handling exception: " + e.getMessage());
                e.printStackTrace();
            }

        }
    }
    static class HttpsRequestHandler implements Runnable{
        private SSLSocket secureSocket;

        public HttpsRequestHandler(SSLSocket secureSocket) {
            this.secureSocket = secureSocket;
        }

        @Override
        public void run(){
            try (InputStream is = secureSocket.getInputStream();
                DataOutputStream os = new DataOutputStream(secureSocket.getOutputStream());
                BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                
                    //Checks session and we don't have connection
                secureSocket.getSession().isValid();
                if (secureSocket.isClosed() || !secureSocket.isConnected()) {
                    System.out.println("Error: Socket is Closed");
                    return;
                }

                // Read the request line
                String requestLine = br.readLine();
                if(requestLine == null)
                    return;

                System.out.println("Received request: " + requestLine );

                    //parsing and setting variables
                String args[];
                String currentDirectory;
                String action, path, contentType = "";
                String version = "HTTP/1.1";
                byte serve[];

                args = requestLine.split(" ");
                action = args[0];
                path = args[1];

                    //sanitizing path and extracting type
                if(path.contains("%"));
                    path = URLDecoder.decode(path, "UTF-8");
                path = sanitizePath(path);
                currentDirectory = sanitizePath(".");

                if(path.contains(".")){
                    contentType = contentType(path.substring(path.indexOf('.')+1, path.length()));
                }

                    //Checking action is get and file exists and is in server directory path
                    //Catches errors, and create webpage for client
                if(action.toLowerCase().contentEquals("get") && inDirectory(currentDirectory, path)){

                    File requestedFile = new File(currentDirectory,path);
                    if(requestedFile.exists() && !requestedFile.isDirectory()){
                        FileInputStream requestedContentFile = new FileInputStream(requestedFile.getCanonicalPath());

                        serve = requestedContentFile.readAllBytes();
                        printResponse(os,version , "200 OK", contentType, serve);
                        requestedContentFile.close();
                        
                    }else{
                        System.out.println("Not Found: " + "." + path);
                        printErrResponse(os, version, "404 Not Found");
                    }
                }else if(action.toLowerCase().contentEquals("get") && !inDirectory(currentDirectory, path)){
                    System.out.println("Forbidden: " + "."+path);
                    printErrResponse(os, version, "403 Forbidden");
                }else{
                    System.out.println("Method Not Allowed: " + action);
                    printErrResponse(os, version, "405 Method Not Allowed");
                }
                
            } catch (IOException e) {
                System.err.println("Request handling exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                    //handles clean socket closure
                try {
                    secureSocket.close();
                } catch (IOException e) {
                    System.err.println("Socket closing exception: " + e.getMessage());
                }
            }
        }
    }

    static class HttpRequestHandler implements Runnable {
        private Socket socket;

        public HttpRequestHandler(Socket socket) {
            this.socket = socket;
        }

        

        @Override
        public void run() {
            try (InputStream is = socket.getInputStream();
                DataOutputStream os = new DataOutputStream(socket.getOutputStream());
                BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

                // Read the request line
                String requestLine = br.readLine();
                if(requestLine == null)
                    return;

                System.out.println("Received request: " + requestLine);

                    //parsing and setting variables
                String args[];
                String action, path, contentType = "";
                String currentDirectory;
                String version = "HTTP/1.1";
                byte serve[];

                args = requestLine.split(" ");
                action = args[0];
                path = args[1];

                    //sanitizing path and extracting type
                if(path.contains("%"));
                    path = URLDecoder.decode(path, "UTF-8");
                path = sanitizePath(path);
                currentDirectory = sanitizePath(".");


                if(path.contains(".")){
                    contentType = contentType(path.substring(path.indexOf('.')+1, path.length()));
                }

                    //Checking action is get and file exists and is in server directory path
                    //Catches errors, and create webpage for client
                if(action.toLowerCase().contentEquals("get") && inDirectory(currentDirectory, path)){

                    File requestedFile = new File(currentDirectory,path);
                    if(requestedFile.exists() && !requestedFile.isDirectory()){
                        FileInputStream requestedContentFile = new FileInputStream(requestedFile.getCanonicalPath());

                        serve = requestedContentFile.readAllBytes();
                        printResponse(os,version , "200 OK", contentType, serve);
                        requestedContentFile.close();
                        
                    }else{
                        System.out.println("Not Found: " + "." + path);
                        printErrResponse(os, version, "404 Not Found");
                    }
                }else if(action.toLowerCase().contentEquals("get") && !inDirectory(currentDirectory, path)){
                    System.out.println("Forbidden: " + "."+path);
                    printErrResponse(os, version, "403 Forbidden");
                }else{
                    System.out.println("Method Not Allowed: " + action);
                    printErrResponse(os, version, "405 Method Not Allowed");
                }
                
            } catch (IOException e) {
                System.err.println("Request handling exception: " + e.getMessage());
                e.printStackTrace();
            } finally { 
                    //handles clean socket closure
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Socket closing exception: " + e.getMessage());
                }
            }
        }
    }
    public static String contentType(String contentExtension){

                //Reading requested content extensions and giving the MIME type
            String mimeType;
            switch (contentExtension) {
                case "html":
                    mimeType = "text/html";
                    break;
                case "jpeg":
                case "gif":
                case "jpg":
                case "png":
                    mimeType = "image/" + contentExtension;
                    break;
                case "ico":
                    mimeType = "image/x-icon";
                    break;
                default:
                    mimeType = "application/octet-stream";
                    break;
            }

            return mimeType;
        }

            //returns path free of ../ and // duplicates
        public static String sanitizePath(String path) throws IOException{
            path = path.replaceAll("//", "/");

            File sanitizedPath = new File(path);            

            return sanitizedPath.getCanonicalPath();
        }

            //checks if path to file is in current directory
        public static Boolean inDirectory(String currentDirectory, String path) throws IOException{
            File directory = new File(currentDirectory);
            File requestedFile = new File(currentDirectory, path);

            String currentCanonical = directory.getCanonicalPath();
            String pathCanonical = requestedFile.getCanonicalPath();


            return pathCanonical.startsWith(currentCanonical);
        }

            //serving requested content back to client
        public static void printResponse(DataOutputStream os,String version,  String serverResponse, String contentType, byte[] serve) throws IOException {
            String response = version + " " + serverResponse + "\r\n" +
                                    "Content-Type: " + contentType + "\r\n" +
                                    "Content-Length: " + serve.length + "\r\n" +
                                    "\r\n";
                os.writeBytes(response);
                os.write(serve);
        }
            //serving error page back to client
        public static void printErrResponse(DataOutputStream os,String version, String serverResponse) throws IOException {
            byte serve[] = ("<html><body><h1>" + serverResponse + "</h1></body></html>").getBytes();
            String contentType = "text/html";
            String response = version + " " + serverResponse + "\r\n" +
                                    "Content-Type: " + contentType + "\r\n" +
                                    "Content-Length: " + serve.length + "\r\n" +
                                    "\r\n";
                os.writeBytes(response);
                os.write(serve);
        }

} 