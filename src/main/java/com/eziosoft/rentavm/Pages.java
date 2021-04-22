package com.eziosoft.rentavm;

import com.eziosoft.rentavm.objects.Session;
import com.eziosoft.rentavm.objects.User;
import com.rethinkdb.gen.ast.Http;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.commons.io.IOUtils;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.helpers.Util;

import javax.xml.crypto.Data;
import java.io.IOException;
import java.net.HttpCookie;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.eziosoft.rentavm.Main.getPageFromResource;
import static com.eziosoft.rentavm.Main.queryToMap;

public class Pages {

    private static Random random = new Random();

    // function to send error pages to the client
    private static void sendErrorPage(int errorcode, HttpExchange e) throws IOException {
        String errorPage = getPageFromResource("/src/error/" + Integer.toString(errorcode) + ".html");
        e.sendResponseHeaders(errorcode, errorPage.length());
        e.getResponseBody().write(errorPage.getBytes(StandardCharsets.UTF_8));
        // close the stream
        e.getResponseBody().close();
    }

    private static String getLoggedInUserName(HttpExchange e){
        String username = "guest";
        List<String> h = e.getRequestHeaders().get("Cookie");
        if (h != null){
            String token = h.get(0).split("=")[1];
            if (Database.checkSessionValid(token)){
                username = Database.getSession(token).getOwner();
            }
        }
        return username;
    }

    private static String getSessionToken(HttpExchange e){
        return e.getRequestHeaders().get("Cookie").get(0).split("=")[1];
    }

    static class landing implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String username = getLoggedInUserName(t);
            String what = getPageFromResource("/src/main.html");
            what = what.replace("{{$USER$}}", username);
            t.sendResponseHeaders(200, what.length());
            t.getResponseBody().write(what.getBytes());
        }
    }

    static class loginFolder implements HttpHandler{
        public void handle(HttpExchange t) throws IOException{
            String filename = t.getRequestURI().getPath();
            //debug shit System.out.println(filename);
            // check if resource exists
            if (Main.class.getResource("/src" + filename) == null){
                sendErrorPage(404, t);
                return;
            }
            if (filename.equals("/login/") || filename.equals("/login")){
                // return index.html
                String what = getPageFromResource("/src"+filename + "/" +"index.html");
                t.sendResponseHeaders(200, what.length());
                t.getResponseBody().write(what.getBytes());
                t.getResponseBody().close();
                return;
            }
            String what = getPageFromResource("/src"+filename);
            t.sendResponseHeaders(200, what.length());
            t.getResponseBody().write(what.getBytes());
            t.getResponseBody().close();
        }
    }

    static class dologin implements HttpHandler{
        public void handle(HttpExchange t) throws IOException{
            // get the post body
            Map<String, String> data = queryToMap(IOUtils.toString(t.getRequestBody(), Charset.defaultCharset()));
            if (data.size() == 1 || !data.containsKey("username")){
                sendErrorPage(403, t);
                return;
            }
            if (data.get("username").isBlank() || data.get("password").isBlank()){
                sendErrorPage(582, t);
                return;
            }
            // check to see if username is valid/exists
            if (!Database.checkForUser(data.get("username"))){
                sendErrorPage(584, t);
                return;
            }
            // load user object from database
            User u = Database.getUser(data.get("username"));
            // compared password to hashed pass
            if (!BCrypt.hashpw(data.get("password"), Main.conf.getSalt()).equals(u.getPasshash())){
                Utilities.doLog("Failed login attempt for this user account", u.getUsername());
                sendErrorPage(583, t);
                return;
            }
            Session s = createSession(u);
            // delete any old sessions rotting away first
            Database.deleteAllSessionsFromUser(s.getOwner());
            Database.insertSession(s);
            t.getResponseHeaders().add("Set-Cookie", "token="+s.getToken()+"; Path=/");
            sendErrorPage(585, t);
            Utilities.doLog("user logged in", s.getOwner());
            return;
        }
    }

    static class doLogout implements HttpHandler{
        public void handle(HttpExchange e) throws IOException {
            if (e.getRequestHeaders().get("Cookie") == null){
                sendErrorPage(403, e);
                return;
            }
            String token = e.getRequestHeaders().get("Cookie").get(0).split("=")[1];
            Database.deleteSession(token);
            sendErrorPage(586, e);
            return;
        }
    }

    static class membersFolder implements HttpHandler{
        public void handle(HttpExchange e) throws IOException {
            // check to make sure the user is logged in (or atleast has a valid cookie)
            if (e.getRequestHeaders().get("Cookie") == null || !Database.checkSessionValid(getSessionToken(e))){
                sendErrorPage(401, e);
                return;
            }
            String requestedDocument = e.getRequestURI().getPath();
            if (Pages.class.getResource("/src" + requestedDocument) == null){
                sendErrorPage(404, e);
                return;
            }
            String page;
            if (requestedDocument.equals("/members/") || requestedDocument.equals("/members")){
                // return index.html
                page = getPageFromResource("/src" + requestedDocument + "/" + "index.html");
            } else {
                page = getPageFromResource("/src" + requestedDocument);
            }
            // check what, if any, special processing is required for the page
            if (page.contains("{{$USER$}}")){
                page = page.replace("{{$USER$}}", getLoggedInUserName(e));
            }
            if (page.contains("{{$FREESTAT$}}")){
                page = page.replace("{{$FREESTAT$}}", Utilities.generateVMStats(getLoggedInUserName(e)));
            }
            if (page.contains("{{$CONTSTAT$}}")){
                page = page.replace("{{$CONTSTAT$}}", "Unimplimented!");
            }
            if (page.contains("{{$WINSTAT$}}")){
                page = page.replace("{{$WINSTAT$}}", "Unimplimented!");
            }
            // send the page that has finished processing
            e.sendResponseHeaders(200, page.length());
            e.getResponseBody().write(page.getBytes(StandardCharsets.UTF_8));
            e.getResponseBody().close();
        }
    }

    static class doRegister implements HttpHandler{
        public void handle(HttpExchange e) throws IOException {
            // get post data
            Map<String, String> data = queryToMap(IOUtils.toString(e.getRequestBody(), Charset.defaultCharset()));
            if (data.size() == 1 || !data.containsKey("username")){
                sendErrorPage(403, e);
                return;
            }
            // did the user leave any of the fields blank?
            if (data.get("username").isBlank() || data.get("email").isBlank() || data.get("pass1").isBlank() || data.get("pass2").isBlank()){
                sendErrorPage(580, e);
                return;
            }
            // check if provided passwords even match
            if (!data.get("pass1").equals(data.get("pass2"))){
                sendErrorPage(578, e);
                return;
            }
            // look and see if the username is taken
            if (Database.checkForUser(data.get("username"))){
                sendErrorPage(579, e);
                return;
            }
            // create new user object
            User u = new User(data.get("username"), BCrypt.hashpw(data.get("pass1"), Main.conf.getSalt()), data.get("email"));
            // insert into database
            Database.insertUser(u);
            Session s = createSession(u);
            Database.insertSession(s);
            // spit cookie onto client
            e.getResponseHeaders().add("Set-Cookie", "token="+s.getToken()+"; Path=/");
            sendErrorPage(581, e);
            Utilities.doLog("user account created", u.getUsername());
            return;
        }
    }

    private static Session createSession(User u){
        /* while here, lets take a moment to generate a session in the database
            a session entry has a few different parts
            - session id, generated from entered username, randomly generated values and system time, Bcrypted and base64'd
            - when it expires stored as miliseconds
            - what account it belongs too
             */
        String base = u.getUsername() + random.nextInt() + System.currentTimeMillis();
        String token = Base64.getEncoder().encodeToString(BCrypt.hashpw(base, BCrypt.gensalt()).getBytes(StandardCharsets.UTF_8));
        // make the session itself
        Session s = new Session(token, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(15), u.getUsername());
        return s;
    }

    static class registerFolder implements HttpHandler{
        public void handle(HttpExchange t) throws IOException{
            // basically ripoff what we did for the login folder, but now for registrations!
            String requestedDocument = t.getRequestURI().getPath();
            if (Pages.class.getResource("/src" + requestedDocument) == null){
                sendErrorPage(404, t);
                return;
            }
            if (requestedDocument.equals("/register/") || requestedDocument.equals("/register")){
                // return index.html
                requestedDocument = getPageFromResource("/src" + requestedDocument + "/" + "index.html");
                t.sendResponseHeaders(200, requestedDocument.length());
                t.getResponseBody().write(requestedDocument.getBytes(StandardCharsets.UTF_8));
                t.getResponseBody().close();
                return;
            }
            String page = getPageFromResource("/src" + requestedDocument);
            t.sendResponseHeaders(200, page.length());
            t.getResponseBody().write(page.getBytes(StandardCharsets.UTF_8));
            t.getResponseBody().close();
        }
    }
}
