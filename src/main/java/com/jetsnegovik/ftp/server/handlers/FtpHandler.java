package com.jetsnegovik.ftp.server.handlers;

import com.jetsnegovik.ftp.server.utils.CommandException;
import com.jetsnegovik.ftp.server.utils.FtpType;
import com.jetsnegovik.ftp.server.Server;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Stack;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Вадим
 */
public class FtpHandler extends SimpleChannelInboundHandler<String> {

    private static final Charset ASCII = Charset.forName("ASCII");
    private static final Logger logger = LoggerFactory.getLogger(FtpHandler.class);
    private static final int CODE_READ_DIR = 150;
    private static final int CODE_SYSTEM_TYPE = 215;
    private static final int CODE_CONNECT_SUCCESS = 220;
    private static final int CODE_OUT = 221;
    private static final int CODE_LOGIN_SUCCESS = 230;
    private static final int CODE_COMMAND_SUCCESS = 215;
    private static final int CODE_ACTION_OK = 250;
    private static final int CODE_TRANSFER_COMPLETE = 226;
    private static final int CODE_ROOT_DIRECTORY = 257;
    private static final int CODE_PASSWORD_NEEDED = 331;
    private static final int CODE_ACTION_PENDING = 350;
    private static final int CODE_ERROR_READ_DIR = 425;
    private static final int CODE_EXCEPTION = 500;
    private static final int CODE_IN_PARAMETERS = 501;
    private static final int CODE_USERNAME_NEEDED = 503;
    private static final int CODE_NOT_LOGGED_IN = 530;
    private static final int CODE_NOT_FOUND = 550;
    private static final int CODE_IO_ERROR = 553;
    private static String username;
    private static String password;
    private static InetSocketAddress addressClient = null;
    private static final String baseDir = System.getProperty("user.dir");
    private static String currentDir = "/";
    private static FtpType ftpType;
    private static File renameFile;

    /**
     * Reflection
     */
    private final Class[] commandHandlerArgTypes = {String.class, StringTokenizer.class, ChannelHandlerContext.class};

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException) {
            ctx.channel().close();
            return;
        }
        logger.error(cause.getMessage(), cause.getCause());
        if (ctx.channel().isOpen()) {
            ctx.channel().close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        send(CODE_CONNECT_SUCCESS, "FTP server (" + Server.VERSION + ") ready.", ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        StringTokenizer st = new StringTokenizer(msg);
        String command = st.nextToken().toLowerCase();

        logger.info("Line: {}", msg);
        logger.info("Command: {}", command);
        Object args[] = {msg, st, ctx};
        try {
            Method commandHandler = getClass().getMethod("command_" + command, commandHandlerArgTypes);
            commandHandler.invoke(this, args);
        } catch (InvocationTargetException e) {
            CommandException ce = (CommandException) e.getTargetException();
            send(ce.getCode(), ce.getText(), ctx);
        } catch (NoSuchMethodException e) {
            send(CODE_EXCEPTION, "'" + msg + "': command not support.", ctx);
        }
    }

    /**
     * Save username from client
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_user(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        username = st.nextToken();
        logger.info("Username: {}", username);
        send(CODE_PASSWORD_NEEDED, "Password required for " + username + ".", ctx);
    }

    /**
     * Check password from client
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_pass(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        if (username == null) {
            throw new CommandException(CODE_USERNAME_NEEDED, "Login with username first.");
        }
        if (st.hasMoreTokens()) {
            password = st.nextToken();
        } else {
            password = "";
        }

        if (!(username.equals("morf") && password.equals("123"))) {
            throw new CommandException(CODE_NOT_LOGGED_IN, "Login incorrect.");
        }
        send(CODE_LOGIN_SUCCESS, "Authorization has been successfully.", ctx);
    }

    /**
     * Send system info
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_syst(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        send(CODE_SYSTEM_TYPE, "UNIX", ctx);
    }

    /**
     * Client info
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_clnt(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        logger.debug("Client: {}", line);
        send(CODE_COMMAND_SUCCESS, "CLNT command successful.", ctx);
    }

    /**
     * Print work directory
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_pwd(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        send(CODE_ROOT_DIRECTORY, currentDir, ctx);
    }

    public void command_type(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken().toUpperCase();
        if (arg.length() != 1) {
            throw new CommandException(CODE_EXCEPTION, "TYPE: invalid argument '" + arg + "'");
        }
        char code = arg.charAt(0);
        ftpType = new FtpType(code);
        send(CODE_COMMAND_SUCCESS, "Type set to " + code, ctx);
    }

    /**
     * Get address client
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_port(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        addressClient = parsePortArgs(st.nextToken());
        if (null == addressClient) {
            throw new CommandException(CODE_IN_PARAMETERS, "Syntax error in parameters or arguments");
        }
        logger.info("Client host: {}, port: {}", addressClient.getAddress(), addressClient.getPort());
        send(CODE_COMMAND_SUCCESS, "PORT command successful.", ctx);
    }

    /**
     * List files current directory
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_list(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String path;
        if (st.hasMoreTokens()) {
            path = st.nextToken();
        } else {
            path = currentDir;
        }
        logger.info("Path: {}", path);
        path = createNativePath(path);

        Socket clientSocket = null;
        try {
            File dir = new File(path);
            String fileNames[] = dir.list();
            int numFiles = fileNames != null ? fileNames.length : 0;
            clientSocket = new Socket(addressClient.getAddress(), addressClient.getPort());
            OutputStreamWriter out = ftpType.getStreamWriter(clientSocket);
            send(CODE_READ_DIR, "Opening data connection.", ctx);
            out.write("total " + numFiles + "\n");
            for (int i = 0; i < numFiles; i++) {
                String fileName = fileNames[i];

                File file = new File(dir, fileName);
                listFile(file, out);
            }
            out.write("\r\n");
            out.flush();

            send(CODE_TRANSFER_COMPLETE, "Transfer complete.", ctx);
        } catch (ConnectException e) {
            throw new CommandException(CODE_ERROR_READ_DIR, "Can't open data connection.");
        } catch (IOException ex) {
            logger.error("Cannot write to client", ex);
            throw new CommandException(CODE_NOT_FOUND, "No such directory.");
        } finally {
            try {
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
            }
        }
    }

    /**
     * Get list is new dir
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_cwd(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String newDir = arg;
        if (newDir.length() == 0) {
            newDir = "/";
        }
        newDir = resolvePath(newDir);

        File file = new File(createNativePath(newDir));
        if (!file.exists()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": no such directory");
        }
        if (!file.isDirectory()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": not a directory");
        }

        currentDir = newDir;
        logger.info("New current dir: {}", currentDir);
        send(CODE_ACTION_OK, "CWD command successful.", ctx);
    }

    /**
     * Send file to client
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_retr(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String path = null;
        try {
            path = line.substring(5);
        } catch (Exception e) {
            throw new NoSuchElementException(e.getMessage());
        }
        path = createNativePath(path);
        logger.info("Send try file: {}", path);

        FileInputStream fis = null;
        Socket dataSocket = null;
        try {
            File file = new File(path);
            if (!file.isFile()) {
                throw new CommandException(CODE_NOT_FOUND, "Not a plain file.");
            }
            fis = new FileInputStream(file);
            dataSocket = new Socket(addressClient.getAddress(), addressClient.getPort());
            send(CODE_READ_DIR, "Opening data connection.", ctx);
            OutputStream out = dataSocket.getOutputStream();
            byte buf[] = new byte[1024 * 64]; //64 kb
            int nread;
            while ((nread = fis.read(buf)) > 0) {
                out.write(buf, 0, nread);
            }
            out.close();
            send(CODE_TRANSFER_COMPLETE, "Transfer complete.", ctx);
        } catch (FileNotFoundException e) {
            throw new CommandException(CODE_NOT_FOUND, "No such file.");
        } catch (IOException e) {
            throw new CommandException(CODE_IO_ERROR, "IO exception");
        } finally {
            try {
                if (fis != null) {
                    fis.close();
                }
                if (dataSocket != null) {
                    dataSocket.close();
                }
            } catch (IOException e) {
            }
        }
    }

    /**
     * Create directory
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_mkd(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String dirPath = resolvePath(arg);
        File dir = new File(createNativePath(dirPath));
        if (dir.exists()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": file exists");
        }
        if (!dir.mkdir()) {
            throw new CommandException(CODE_EXCEPTION, arg + ": directory could not be created");
        }
        send(CODE_ROOT_DIRECTORY, "\"" + dirPath + "\" directory created", ctx);
    }

    /**
     * Upload file to server
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_stor(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String path = null;
        try {
            path = line.substring(5);
        } catch (Exception e) {
            throw new NoSuchElementException(e.getMessage());
        }
        path = createNativePath(path);
        logger.info("Upload file to: {}", path);

        FileOutputStream out = null;
        Socket dataSocket = null;
        try {
            File file = new File(path);
            if (file.exists()) {
                throw new CommandException(CODE_NOT_FOUND, "File exists in that location.");
            }
            out = new FileOutputStream(file);
            dataSocket = new Socket(addressClient.getAddress(), addressClient.getPort());
            send(CODE_READ_DIR, "Opening data connection.", ctx);

            InputStream in = dataSocket.getInputStream();
            int bufSize = 1024 * 64;
            byte buf[] = new byte[bufSize];
            int nread;
            while ((nread = in.read(buf, 0, bufSize)) > 0) {
                out.write(buf, 0, nread);
            }
            in.close();
            send(CODE_TRANSFER_COMPLETE, "Transfer complete.", ctx);
        } catch (FileNotFoundException e) {
            throw new CommandException(CODE_NOT_FOUND, "No such file.");
        } catch (IOException e) {
            throw new CommandException(CODE_IO_ERROR, "IO exception");
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (dataSocket != null) {
                    dataSocket.close();
                }
            } catch (IOException e) {
            }
        }
    }

    /**
     * Remove file in server
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_dele(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String filePath = resolvePath(arg);
        File file = new File(createNativePath(filePath));
        if (!file.exists()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": file does not exist");
        }
        if (!file.delete()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": could not delete file");
        }
        send(CODE_ACTION_OK, "DELE command successful.", ctx);
    }

    /**
     * Pending file to rename
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_rnfr(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String filePath = resolvePath(arg);
        renameFile = new File(createNativePath(filePath));
        if (!renameFile.exists()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": file does not exist");
        }
        send(CODE_ACTION_PENDING, "Pending file", ctx);
    }

    /**
     * Rename file
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_rnto(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String filePath = resolvePath(arg);
        File newFile = new File(createNativePath(filePath));
        if (renameFile.renameTo(newFile)) {
            renameFile = null;
            send(CODE_ACTION_OK, "CWD rnto success", ctx);
        } else {
            throw new CommandException(CODE_NOT_FOUND, arg + ": file does not exist");
        }
    }

    /**
     * Connection close
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void handle_quit(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        username = null;
        password = null;
        send(CODE_OUT, "Goodbye...", ctx);
        ctx.channel().close();
    }

    /**
     * Detele directory
     *
     * @param line
     * @param st
     * @param ctx
     * @throws CommandException
     */
    public void command_rmd(String line, StringTokenizer st, ChannelHandlerContext ctx) throws CommandException {
        checkLogin();
        String arg = st.nextToken();
        String dirPath = resolvePath(arg);
        File dir = new File(createNativePath(dirPath));
        if (!dir.exists()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": directory does not exist");
        }
        if (!dir.isDirectory()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": not a directory");
        }
        if (!dir.delete()) {
            throw new CommandException(CODE_NOT_FOUND, arg + ": could not remove directory");
        }
        send(CODE_ACTION_OK, "RMD command successful.", ctx);
    }

    private String resolvePath(String path) {
        if (path.charAt(0) != '/') {
            path = currentDir + "/" + path;
        }
        StringTokenizer pathSt = new StringTokenizer(path, "/");
        Stack segments = new Stack();
        while (pathSt.hasMoreTokens()) {
            String segment = pathSt.nextToken();
            if (segment.equals("..")) {
                if (!segments.empty()) {
                    segments.pop();
                }
            } else if (segment.equals(".")) {
                // skip
            } else {
                segments.push(segment);
            }
        }

        StringBuilder pathBuf = new StringBuilder("/");
        Enumeration segmentsEn = segments.elements();
        while (segmentsEn.hasMoreElements()) {
            pathBuf.append(segmentsEn.nextElement());
            if (segmentsEn.hasMoreElements()) {
                pathBuf.append("/");
            }
        }
        return pathBuf.toString();
    }

    private void listFile(File file, OutputStreamWriter out) throws IOException {
        Date date = new Date(file.lastModified());
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd hh:mm", new Locale("en", "US"));
        String dateStr = dateFormat.format(date);

        long size = file.length();
        String sizeStr = Long.toString(size);
        int sizePadLength = Math.max(8 - sizeStr.length(), 0);
        String sizeField = pad(sizePadLength) + sizeStr;

        out.write(file.isDirectory() ? 'd' : '-');
        out.write("rwxrwxrwx");
        out.write(" ");
        out.write("1");
        out.write(" ");
        out.write("ftp"); //owner:group
        out.write(" ");
        out.write("ftp"); //group
        out.write(" ");
        out.write(sizeField);
        out.write(" ");
        out.write(dateStr);
        out.write(" ");
        out.write(file.getName());
        out.write('\n');
    }

    private static String pad(int length) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < length; i++) {
            buf.append(' ');
        }
        return buf.toString();
    }

    private String createNativePath(String ftpPath) {
        String path;
        if (ftpPath.charAt(0) == '/') {
            path = baseDir + ftpPath;
        } else {
            path = baseDir + currentDir + "/" + ftpPath;
        }
        logger.info("Absolute path: {}", path);
        return path;
    }

    private static InetSocketAddress parsePortArgs(String portArgs) {
        String[] strParts = portArgs.split(",");
        if (strParts.length != 6) {
            return null;
        }
        byte[] address = new byte[4];
        int[] parts = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                parts[i] = Integer.parseInt(strParts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (parts[i] < 0 || parts[i] > 255) {
                return null;
            }
        }
        for (int i = 0; i < 4; i++) {
            address[i] = (byte) parts[i];
        }
        int port = parts[4] << 8 | parts[5];
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            return null;
        }
        return new InetSocketAddress(inetAddress, port);
    }

    private void checkLogin() throws CommandException {
        if (username == null || password == null) {
            throw new CommandException(CODE_NOT_LOGGED_IN, "Please login with username and password.");
        }
    }

    private static void send(int code, String response, ChannelHandlerContext ctx) {
        logger.info("Code: {}, Text: {}", code, response);
        String line = code + " " + response + "\r\n";
        byte[] data = line.getBytes(ASCII);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(data));
    }
}
