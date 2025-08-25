import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    // ---------- Server ----------
    private static int PORT = 8080;
    private static HttpServer server;

    // ---------- Simple in-memory session store ----------
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final Random random = new Random();

    // ---------- DB ----------
    private static final String DB_URL = "jdbc:sqlite:bus.db";

    public static void main(String[] args) throws Exception {
        // Port select
        if (args.length > 0) {
            try { PORT = Integer.parseInt(args[0]); } catch (Exception e) { PORT = findAvailablePort(); }
        } else PORT = findAvailablePort();

        // Ensure driver
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.out.println("SQLite JDBC driver not found. Add dependency: org.xerial:sqlite-jdbc");
            return;
        }

        // DB init + seed
        initDb();
        seedDb();

        // Start server
        startServer();
        System.out.println("✅ সার্ভার চালু হয়েছে পোর্টে: " + PORT);
        System.out.println("🌐 খুলুন: http://localhost:" + PORT + "/");
        System.out.println("👤 Admin: admin/admin123 | User: user/user123");
    }

    // ---------- Utilities ----------
    private static int findAvailablePort() {
        for (int p = 8080; p <= 8090; p++) {
            try (ServerSocket s = new ServerSocket(p)) { return p; } catch (IOException ignored) {}
        }
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
        catch (IOException e) { return 8080; }
    }

    private static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Public / Login
        server.createContext("/", new LoginHandler());
        server.createContext("/login", new LoginHandler());
        server.createContext("/logout", new LogoutHandler());

        // Admin
        server.createContext("/admin", requireRole("admin", new AdminDashboardHandler()));
        server.createContext("/admin/buses", requireRole("admin", new BusesHandler()));
        server.createContext("/admin/buses/add", requireRole("admin", new AddBusHandler()));
        server.createContext("/admin/buses/delete", requireRole("admin", new DeleteBusHandler()));

        server.createContext("/admin/routes", requireRole("admin", new RoutesHandler()));
        server.createContext("/admin/routes/add", requireRole("admin", new AddRouteHandler()));
        server.createContext("/admin/routes/delete", requireRole("admin", new DeleteRouteHandler()));

        server.createContext("/admin/schedules", requireRole("admin", new SchedulesHandler()));
        server.createContext("/admin/schedules/add", requireRole("admin", new AddScheduleHandler()));
        server.createContext("/admin/schedules/delete", requireRole("admin", new DeleteScheduleHandler()));

        server.createContext("/admin/bookings", requireRole("admin", new BookingsHandler()));
        server.createContext("/admin/bookings/status", requireRole("admin", new BookingStatusHandler()));
        server.createContext("/admin/bookings/delete", requireRole("admin", new BookingDeleteHandler()));

        // User
        server.createContext("/user", requireRole("user", new UserDashboardHandler()));
        server.createContext("/user/search", requireRole("user", new SearchHandler()));
        server.createContext("/user/book", requireRole("user", new BookHandler()));

        server.setExecutor(null);
        server.start();
    }

    // Middleware wrapper to enforce role
    private static HttpHandler requireRole(String role, HttpHandler next) {
        return exchange -> {
            Session s = getSession(exchange);
            if (s == null || (role != null && !role.equalsIgnoreCase(s.role))) {
                redirect(exchange, "/");
                return;
            }
            next.handle(exchange);
        };
    }

    // ---------- DB Setup ----------
    private static void initDb() {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users(
                      username TEXT PRIMARY KEY,
                      password TEXT NOT NULL,
                      role     TEXT NOT NULL
                    );
                """);
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS buses(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      type TEXT,
                      capacity INTEGER NOT NULL
                    );
                """);
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS routes(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      source TEXT NOT NULL,
                      destination TEXT NOT NULL,
                      fare REAL NOT NULL
                    );
                """);
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schedules(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      bus_id INTEGER NOT NULL,
                      route_id INTEGER NOT NULL,
                      date TEXT NOT NULL,
                      time TEXT NOT NULL,
                      FOREIGN KEY(bus_id) REFERENCES buses(id) ON DELETE CASCADE,
                      FOREIGN KEY(route_id) REFERENCES routes(id) ON DELETE CASCADE
                    );
                """);
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bookings(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      phone TEXT NOT NULL,
                      schedule_id INTEGER NOT NULL,
                      seat_no INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      total REAL NOT NULL,
                      created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                      UNIQUE(schedule_id, seat_no),
                      FOREIGN KEY(schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
                    );
                """);
                st.executeUpdate("PRAGMA foreign_keys = ON;");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB init failed: " + e.getMessage(), e);
        }
    }

    private static void seedDb() {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            // users
            if (!exists(c, "SELECT 1 FROM users LIMIT 1")) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO users(username,password,role) VALUES(?,?,?)")) {
                    p.setString(1,"admin"); p.setString(2,"admin123"); p.setString(3,"admin"); p.addBatch();
                    p.setString(1,"user");  p.setString(2,"user123");  p.setString(3,"user");  p.addBatch();
                    p.executeBatch();
                }
            }
            // buses
            if (!exists(c, "SELECT 1 FROM buses LIMIT 1")) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO buses(name,type,capacity) VALUES(?,?,?)")) {
                    p.setString(1,"ঢাকা-চট্ট 01"); p.setString(2,"Non-AC"); p.setInt(3,40); p.addBatch();
                    p.setString(1,"ঢাকা-সিলেট 02"); p.setString(2,"AC");     p.setInt(3,30); p.addBatch();
                    p.executeBatch();
                }
            }
            // routes
            if (!exists(c, "SELECT 1 FROM routes LIMIT 1")) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO routes(source,destination,fare) VALUES(?,?,?)")) {
                    p.setString(1,"ঢাকা"); p.setString(2,"চট্টগ্রাম"); p.setDouble(3,700); p.addBatch();
                    p.setString(1,"ঢাকা"); p.setString(2,"সিলেট");    p.setDouble(3,550); p.addBatch();
                    p.executeBatch();
                }
            }
            // schedules
            if (!exists(c, "SELECT 1 FROM schedules LIMIT 1")) {
                int bus1 = getId(c, "SELECT id FROM buses WHERE name=?","ঢাকা-চট্ট 01");
                int bus2 = getId(c, "SELECT id FROM buses WHERE name=?","ঢাকা-সিলেট 02");
                int r1 = getId(c,"SELECT id FROM routes WHERE destination=?","চট্টগ্রাম");
                int r2 = getId(c,"SELECT id FROM routes WHERE destination=?","সিলেট");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO schedules(bus_id,route_id,date,time) VALUES(?,?,?,?)")) {
                    p.setInt(1,bus1); p.setInt(2,r1); p.setString(3, LocalDate.now().plusDays(1).toString()); p.setString(4,"09:00"); p.addBatch();
                    p.setInt(1,bus2); p.setInt(2,r2); p.setString(3, LocalDate.now().plusDays(2).toString()); p.setString(4,"14:00"); p.addBatch();
                    p.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB seed failed: " + e.getMessage(), e);
        }
    }

    private static boolean exists(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { return rs.next(); }
    }
    private static int getId(Connection c, String sql, String value) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, value);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    // ---------- Session ----------
    static class Session { String username; String role; }
    private static Session getSession(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=",2);
            if (kv.length==2 && kv[0].equals("SESSION")) {
                return sessions.get(kv[1]);
            }
        }
        return null;
    }
    private static void setSession(HttpExchange ex, String username, String role) {
        String token = Long.toHexString(random.nextLong()) + Long.toHexString(System.nanoTime());
        Session s = new Session(); s.username = username; s.role = role;
        sessions.put(token, s);
        ex.getResponseHeaders().add("Set-Cookie", "SESSION="+token+"; HttpOnly; Path=/");
    }
    private static void clearSession(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=",2);
                if (kv.length==2 && kv[0].equals("SESSION")) {
                    sessions.remove(kv[1]);
                }
            }
        }
        ex.getResponseHeaders().add("Set-Cookie","SESSION=deleted; Max-Age=0; Path=/");
    }

    // ---------- HTML helpers ----------
    private static String uiStyles() {
        return """
        <style>
        :root{
          --bg:#0f172a; --card:#0b1220; --muted:#a3b3d9;
          --pri:#7c4dff; --pri2:#00d4ff; --acc:#22c55e; --warn:#f59e0b; --err:#ef4444;
          --grad: linear-gradient(135deg, var(--pri), var(--pri2));
          --soft: linear-gradient(135deg,#111827,#0b1220);
        }
        *{box-sizing:border-box} body{margin:0;font-family:Inter,system-ui,Arial,sans-serif;background:var(--soft);color:#e6ecff}
        .wrap{max-width:1100px;margin:0 auto;padding:28px}
        .hero{background:var(--grad); padding:22px;border-radius:16px; box-shadow:0 12px 30px rgba(0,0,0,.35); color:white}
        .title{margin:0 0 6px 0;font-size:26px;font-weight:800;letter-spacing:.3px}
        .subtitle{margin:0;color:#f1f5ff;opacity:.9}
        .grid{display:grid; grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); gap:16px; margin-top:16px}
        .card{background:rgba(255,255,255,.04); border:1px solid rgba(255,255,255,.08); border-radius:16px; padding:16px; backdrop-filter: blur(6px)}
        .card h3{margin:0 0 10px 0; font-size:18px}
        .btn{display:inline-block; padding:10px 14px; border-radius:10px; text-decoration:none; color:white; background:var(--grad); border:0; cursor:pointer; transition: transform .12s ease}
        .btn:hover{transform: translateY(-1px)}
        .btn.secondary{background:#1f2937}
        .btn.danger{background:linear-gradient(135deg,#ef4444, #f97316)}
        .btn.warn{background:linear-gradient(135deg,#f59e0b,#f97316)}
        .btn.ok{background:linear-gradient(135deg,#22c55e,#10b981)}
        table{width:100%; border-collapse:collapse; margin-top:8px; border-radius:12px; overflow:hidden}
        th,td{padding:10px 12px; border-bottom:1px solid rgba(255,255,255,.06)}
        th{background:rgba(255,255,255,.08); text-align:left}
        tr:hover{background:rgba(255,255,255,.04)}
        input,select{width:100%; padding:10px; border-radius:10px; border:1px solid rgba(255,255,255,.12); background:#0b1220; color:#dfe6ff; outline:none}
        input::placeholder{color:#94a3b8}
        form .row{display:grid; grid-template-columns:repeat(2,1fr); gap:12px}
        .topbar{display:flex; gap:10px; flex-wrap:wrap; margin:14px 0}
        .note{color:var(--muted); font-size:13px}
        .pill{display:inline-block; padding:4px 8px; border-radius:999px; font-size:12px; background:rgba(255,255,255,.08)}
        a.link{color:#8ab4ff}
        </style>
        """;
    }
    private static String pageHeader(String title, String subtitle, Session s) {
        return """
        <!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
        <title>""" + esc(title) + "</title>" + uiStyles() + """
        </head><body><div class='wrap'>
        <div class='hero'>
          <div class='title'>""" + esc(title) + "</div><div class='subtitle'>" + esc(subtitle==null?"":subtitle) + "</div>" + (s!=null?
                ("<div style='margin-top:10px'><span class='pill'>ব্যবহারকারী: "+esc(s.username)+" ("+esc(s.role)+")</span> " +
                        "<a class='btn secondary' href='/logout'>লগআউট</a></div>"):"") + """
        </div>
        """;
    }
    private static String pageFooter() { return "<div class='note' style='margin-top:16px'>ডেমো অ্যাপ • SQLite ফাইল: bus.db</div></div></body></html>"; }
    private static String esc(String s){ if (s==null) return ""; return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    private static void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try(OutputStream os = ex.getResponseBody()){ os.write(bytes); }
    }
    private static void redirect(HttpExchange ex, String path) throws IOException {
        ex.getResponseHeaders().add("Location", path);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static Map<String,String> parseForm(String data){
        Map<String,String> m = new HashMap<>();
        if (data==null || data.isEmpty()) return m;
        for (String kv : data.split("&")){
            String[] parts = kv.split("=",2);
            String k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String v = parts.length>1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            m.put(k,v);
        }
        return m;
    }
    private static Map<String,String> parseQuery(String query){
        Map<String,String> m = new HashMap<>();
        if (query==null || query.isEmpty()) return m;
        for (String kv : query.split("&")){
            String[] parts = kv.split("=",2);
            String k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String v = parts.length>1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            m.put(k,v);
        }
        return m;
    }

    // ---------- Handlers ----------

    // Login + Logout
    static class LoginHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String html = """
                <!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
                <title>লগইন</title>""" + uiStyles() + """
                </head><body><div class='wrap'>
                  <div class='hero'><div class='title'>বাস ম্যানেজমেন্ট সিস্টেম</div>
                  <div class='subtitle'>লগইন করুন (Admin/User)</div></div>

                  <div class='grid'>
                    <div class='card'>
                      <h3>লগইন</h3>
                      <form method='POST' action='/login'>
                        <label>ব্যবহারকারীর নাম</label>
                        <input name='username' placeholder='admin বা user' required>
                        <label>পাসওয়ার্ড</label>
                        <input type='password' name='password' placeholder='***' required>
                        <div class='topbar'><button class='btn'>লগইন</button></div>
                        <div class='note'>ডামি ক্রেডেনশিয়াল — admin/admin123, user/user123</div>
                      </form>
                    </div>
                    <div class='card'>
                      <h3>ফিচারস</h3>
                      <ul>
                        <li>রঙিন Bangla UI, আধুনিক গ্রেডিয়েন্ট</li>
                        <li>Admin: Bus/Route/Schedule/Booking</li>
                        <li>User: Search & Booking (unique seat)</li>
                        <li>SQLite database (file: bus.db)</li>
                      </ul>
                    </div>
                  </div>
                </div></body></html>
                """;
                sendHtml(ex,200,html);
                return;
            }

            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String,String> f = parseForm(body);
                String u = f.getOrDefault("username","").trim();
                String p = f.getOrDefault("password","").trim();

                try (Connection c = DriverManager.getConnection(DB_URL)) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT role FROM users WHERE username=? AND password=?")) {
                        ps.setString(1,u); ps.setString(2,p);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String role = rs.getString(1);
                                setSession(ex, u, role);
                                if ("admin".equalsIgnoreCase(role)) redirect(ex,"/admin");
                                else redirect(ex,"/user");
                                return;
                            }
                        }
                    }
                } catch (SQLException e) {
                    sendHtml(ex,500,"DB error: "+esc(e.getMessage()));
                    return;
                }
                String html = pageHeader("লগইন ব্যর্থ", "অবৈধ ব্যবহারকারীর নাম বা পাসওয়ার্ড", null) +
                        "<div class='card'><a class='btn' href='/'>ফিরে যান</a></div>" + pageFooter();
                sendHtml(ex,200,html);
            }
        }
    }
    static class LogoutHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            clearSession(ex);
            redirect(ex,"/");
        }
    }

    // Admin: Dashboard
    static class AdminDashboardHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            String html = pageHeader("অ্যাডমিন প্যানেল", "ব্যবসায়িক ব্যবস্থাপনা", s) +
                    """
                    <div class='grid'>
                      <div class='card'><h3>বাস নিয়ন্ত্রণ</h3><p class='note'>নতুন বাস যোগ, তালিকা, মুছুন</p><a class='btn' href='/admin/buses'>যান</a></div>
                      <div class='card'><h3>রুট নিয়ন্ত্রণ</h3><p class='note'>সূত্র-গন্তব্য, ভাড়া</p><a class='btn' href='/admin/routes'>যান</a></div>
                      <div class='card'><h3>সিডিউল নিয়ন্ত্রণ</h3><p class='note'>বাস-রুট-সময়</p><a class='btn' href='/admin/schedules'>যান</a></div>
                      <div class='card'><h3>বুকিং সমূহ</h3><p class='note'>স্ট্যাটাস, ডিলিট</p><a class='btn' href='/admin/bookings'>যান</a></div>
                    </div>
                    """ + pageFooter();
            sendHtml(ex,200,html);
        }
    }

    // Admin: Buses
    static class BusesHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            StringBuilder sb = new StringBuilder();
            sb.append(pageHeader("বাস নিয়ন্ত্রণ", "বাস যোগ/তালিকা/ডিলিট", s));
            sb.append("<div class='topbar'><a class='btn secondary' href='/admin'>হোম</a></div>");
            sb.append("<div class='card'><h3>নতুন বাস যোগ</h3><form method='POST' action='/admin/buses/add'>");
            sb.append("<div class='row'><div><label>নাম</label><input name='name' required></div><div><label>ধরণ</label><input name='type' placeholder='AC / Non-AC'></div></div>");
            sb.append("<div class='row'><div><label>সিট সংখ্যা</label><input type='number' name='capacity' value='40' min='1'></div></div>");
            sb.append("<button class='btn ok'>সংরক্ষণ</button></form></div>");

            sb.append("<div class='card'><h3>সব বাস</h3><table><tr><th>ID</th><th>নাম</th><th>ধরণ</th><th>সিট</th><th>কর্ম</th></tr>");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,name,type,capacity FROM buses ORDER BY id DESC")) {
                while (rs.next()){
                    sb.append("<tr><td>").append(rs.getInt(1)).append("</td><td>").append(esc(rs.getString(2)))
                            .append("</td><td>").append(esc(rs.getString(3)==null?"":rs.getString(3)))
                            .append("</td><td>").append(rs.getInt(4))
                            .append("</td><td><a class='btn danger' href='/admin/buses/delete?id=").append(rs.getInt(1)).append("'>মুছুন</a></td></tr>");
                }
            } catch (SQLException e){ sb.append("<tr><td colspan='5'>").append(esc(e.getMessage())).append("</td></tr>"); }
            sb.append("</table></div>");
            sb.append(pageFooter());
            sendHtml(ex,200,sb.toString());
        }
    }
    static class AddBusHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendHtml(ex,405,"Method Not Allowed"); return; }
            Map<String,String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String name = f.getOrDefault("name","").trim();
            String type = f.getOrDefault("type","").trim();
            int capacity = Integer.parseInt(f.getOrDefault("capacity","40"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO buses(name,type,capacity) VALUES(?,?,?)")) {
                ps.setString(1,name); ps.setString(2,type); ps.setInt(3,capacity);
                ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/buses");
        }
    }
    static class DeleteBusHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int id = Integer.parseInt(q.getOrDefault("id","0"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM buses WHERE id=?")) {
                ps.setInt(1,id); ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/buses");
        }
    }

    // Admin: Routes
    static class RoutesHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            StringBuilder sb = new StringBuilder();
            sb.append(pageHeader("রুট নিয়ন্ত্রণ","সূত্র/গন্তব্য/ভাড়া", s));
            sb.append("<div class='topbar'><a class='btn secondary' href='/admin'>হোম</a></div>");
            sb.append("<div class='card'><h3>নতুন রুট যোগ</h3><form method='POST' action='/admin/routes/add'>");
            sb.append("<div class='row'><div><label>সূত্র</label><input name='source' required></div><div><label>গন্তব্য</label><input name='destination' required></div></div>");
            sb.append("<div class='row'><div><label>ভাড়া</label><input type='number' step='0.01' name='fare' value='500'></div></div>");
            sb.append("<button class='btn ok'>সংরক্ষণ</button></form></div>");

            sb.append("<div class='card'><h3>সব রুট</h3><table><tr><th>ID</th><th>সূত্র</th><th>গন্তব্য</th><th>ভাড়া</th><th>কর্ম</th></tr>");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,source,destination,fare FROM routes ORDER BY id DESC")) {
                while (rs.next()){
                    sb.append("<tr><td>").append(rs.getInt(1)).append("</td><td>").append(esc(rs.getString(2)))
                            .append("</td><td>").append(esc(rs.getString(3))).append("</td><td>").append(rs.getDouble(4))
                            .append("</td><td><a class='btn danger' href='/admin/routes/delete?id=").append(rs.getInt(1)).append("'>মুছুন</a></td></tr>");
                }
            } catch (SQLException e){ sb.append("<tr><td colspan='5'>").append(esc(e.getMessage())).append("</td></tr>"); }
            sb.append("</table></div>");
            sb.append(pageFooter());
            sendHtml(ex,200,sb.toString());
        }
    }
    static class AddRouteHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendHtml(ex,405,"Method Not Allowed"); return; }
            Map<String,String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String src = f.getOrDefault("source","").trim();
            String dst = f.getOrDefault("destination","").trim();
            double fare = Double.parseDouble(f.getOrDefault("fare","500"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO routes(source,destination,fare) VALUES(?,?,?)")) {
                ps.setString(1,src); ps.setString(2,dst); ps.setDouble(3,fare);
                ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/routes");
        }
    }
    static class DeleteRouteHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int id = Integer.parseInt(q.getOrDefault("id","0"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM routes WHERE id=?")) {
                ps.setInt(1,id); ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/routes");
        }
    }

    // Admin: Schedules
    static class SchedulesHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            StringBuilder sb = new StringBuilder();
            sb.append(pageHeader("সিডিউল নিয়ন্ত্রণ","বাস-রুট-তারিখ-সময়", s));
            sb.append("<div class='topbar'><a class='btn secondary' href='/admin'>হোম</a></div>");

            // Add form
            sb.append("<div class='card'><h3>নতুন সিডিউল</h3><form method='POST' action='/admin/schedules/add'>");
            sb.append("<div class='row'><div><label>বাস</label><select name='busId'>");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,name,type FROM buses ORDER BY id DESC")) {
                while (rs.next()){
                    sb.append("<option value='").append(rs.getInt(1)).append("'>")
                            .append(esc(rs.getString(2))).append(" (").append(esc(rs.getString(3)==null?"":rs.getString(3))).append(")")
                            .append("</option>");
                }
            } catch (SQLException e) { sb.append("<option>DB error</option>"); }
            sb.append("</select></div><div><label>রুট</label><select name='routeId'>");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id,source,destination FROM routes ORDER BY id DESC")) {
                while (rs.next()){
                    sb.append("<option value='").append(rs.getInt(1)).append("'>")
                            .append(esc(rs.getString(2))).append(" → ").append(esc(rs.getString(3)))
                            .append("</option>");
                }
            } catch (SQLException e) { sb.append("<option>DB error</option>"); }
            sb.append("</select></div></div>");
            sb.append("<div class='row'><div><label>তারিখ</label><input name='date' value='").append(LocalDate.now().plusDays(1)).append("' required></div>")
                    .append("<div><label>সময়</label><input name='time' value='09:00' required></div></div>");
            sb.append("<button class='btn ok'>সংরক্ষণ</button></form></div>");

            // List
            sb.append("<div class='card'><h3>সব সিডিউল</h3><table><tr><th>ID</th><th>বাস</th><th>রুট</th><th>তারিখ</th><th>সময়</th><th>উপলব্ধ সিট</th><th>কর্ম</th></tr>");
            String sql = """
                SELECT s.id, b.name, r.source, r.destination, s.date, s.time,
                  b.capacity - (SELECT COUNT(*) FROM bookings bk WHERE bk.schedule_id=s.id AND bk.status!='CANCELLED') AS avail
                FROM schedules s
                JOIN buses b ON b.id=s.bus_id
                JOIN routes r ON r.id=s.route_id
                ORDER BY s.id DESC
            """;
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()){
                    sb.append("<tr><td>").append(rs.getInt("id")).append("</td><td>").append(esc(rs.getString("name")))
                            .append("</td><td>").append(esc(rs.getString("source")+" → "+rs.getString("destination")))
                            .append("</td><td>").append(esc(rs.getString("date"))).append("</td><td>").append(esc(rs.getString("time")))
                            .append("</td><td>").append(rs.getInt("avail"))
                            .append("</td><td><a class='btn danger' href='/admin/schedules/delete?id=").append(rs.getInt("id")).append("'>মুছুন</a></td></tr>");
                }
            } catch (SQLException e){ sb.append("<tr><td colspan='7'>").append(esc(e.getMessage())).append("</td></tr>"); }
            sb.append("</table></div>");
            sb.append(pageFooter());
            sendHtml(ex,200,sb.toString());
        }
    }
    static class AddScheduleHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendHtml(ex,405,"Method Not Allowed"); return; }
            Map<String,String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int busId = Integer.parseInt(f.getOrDefault("busId","0"));
            int routeId = Integer.parseInt(f.getOrDefault("routeId","0"));
            String date = f.getOrDefault("date","");
            String time = f.getOrDefault("time","");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO schedules(bus_id,route_id,date,time) VALUES(?,?,?,?)")) {
                ps.setInt(1,busId); ps.setInt(2,routeId); ps.setString(3,date); ps.setString(4,time);
                ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/schedules");
        }
    }
    static class DeleteScheduleHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int id = Integer.parseInt(q.getOrDefault("id","0"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM schedules WHERE id=?")) {
                ps.setInt(1,id); ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/schedules");
        }
    }

    // Admin: Bookings
    static class BookingsHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            StringBuilder sb = new StringBuilder();
            sb.append(pageHeader("বুকিং সমূহ","স্ট্যাটাস পরিবর্তন/ডিলিট", s));
            sb.append("<div class='topbar'><a class='btn secondary' href='/admin'>হোম</a></div>");
            sb.append("<div class='card'><table><tr><th>ID</th><th>নাম</th><th>ফোন</th><th>রুট</th><th>তারিখ/সময়</th><th>সিট</th><th>টোটাল</th><th>স্ট্যাটাস</th><th>কর্ম</th></tr>");

            String sql = """
              SELECT bk.id, bk.name, bk.phone, bk.seat_no, bk.total, bk.status,
                     r.source, r.destination, s.date, s.time
              FROM bookings bk
              JOIN schedules s ON s.id=bk.schedule_id
              JOIN routes r ON r.id=s.route_id
              ORDER BY bk.id DESC
            """;
            try (Connection c = DriverManager.getConnection(DB_URL);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()){
                    int id = rs.getInt("id");
                    sb.append("<tr><td>").append(id).append("</td><td>").append(esc(rs.getString("name")))
                            .append("</td><td>").append(esc(rs.getString("phone"))).append("</td><td>")
                            .append(esc(rs.getString("source")+" → "+rs.getString("destination")))
                            .append("</td><td>").append(esc(rs.getString("date")+" "+rs.getString("time")))
                            .append("</td><td>").append(rs.getInt("seat_no")).append("</td><td>")
                            .append(rs.getDouble("total")).append("</td><td>").append(esc(rs.getString("status")))
                            .append("</td><td>")
                            .append("<a class='btn ok' href='/admin/bookings/status?id=").append(id).append("&st=COMPLETED'>সম্পন্ন</a> ")
                            .append("<a class='btn warn' href='/admin/bookings/status?id=").append(id).append("&st=CANCELLED'>ক্যানসেল</a> ")
                            .append("<a class='btn danger' href='/admin/bookings/delete?id=").append(id).append("'>মুছুন</a>")
                            .append("</td></tr>");
                }
            } catch (SQLException e){ sb.append("<tr><td colspan='9'>").append(esc(e.getMessage())).append("</td></tr>"); }
            sb.append("</table></div>");
            sb.append(pageFooter());
            sendHtml(ex,200,sb.toString());
        }
    }
    static class BookingStatusHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int id = Integer.parseInt(q.getOrDefault("id","0"));
            String st = q.getOrDefault("st","CONFIRMED");
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("UPDATE bookings SET status=? WHERE id=?")) {
                ps.setString(1,st); ps.setInt(2,id); ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/bookings");
        }
    }
    static class BookingDeleteHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int id = Integer.parseInt(q.getOrDefault("id","0"));
            try (Connection c = DriverManager.getConnection(DB_URL);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM bookings WHERE id=?")) {
                ps.setInt(1,id); ps.executeUpdate();
            } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }
            redirect(ex,"/admin/bookings");
        }
    }

    // User: Dashboard, Search, Book
    static class UserDashboardHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            String html = pageHeader("ব্যবহারকারী প্যানেল","টিকিট খুঁজুন ও বুক করুন", s) +
                    "<div class='grid'>" +
                    "<div class='card'><h3>সিডিউল খুঁজুন</h3><p class='note'>সূত্র/গন্তব্য/তারিখ</p><a class='btn' href='/user/search'>যান</a></div>" +
                    "</div>" + pageFooter();
            sendHtml(ex,200,html);
        }
    }
    static class SearchHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String html = pageHeader("সিডিউল খুঁজুন","আপনার রুট অনুসন্ধান করুন", s) +
                        "<div class='topbar'><a class='btn secondary' href='/user'>হোম</a></div>" +
                        "<div class='card'><form method='POST' action='/user/search'>" +
                        "<div class='row'><div><label>সূত্র</label><input name='source' required></div>" +
                        "<div><label>গন্তব্য</label><input name='destination' required></div></div>" +
                        "<div class='row'><div><label>তারিখ</label><input name='date' value='"+ LocalDate.now().plusDays(1) +"'></div><div></div></div>" +
                        "<button class='btn'>খুঁজুন</button></form></div>" +
                        pageFooter();
                sendHtml(ex,200,html);
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                Map<String,String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String src = f.getOrDefault("source","").trim();
                String dst = f.getOrDefault("destination","").trim();
                String date = f.getOrDefault("date","").trim();

                StringBuilder sb = new StringBuilder();
                sb.append(pageHeader("সার্চ ফলাফল", src+" → "+dst, s));
                sb.append("<div class='topbar'><a class='btn secondary' href='/user/search'>নতুন সার্চ</a></div>");
                sb.append("<div class='card'><table><tr><th>বাস</th><th>রুট</th><th>তারিখ</th><th>সময়</th><th>উপলব্ধ সিট</th><th>ভাড়া</th><th>কর্ম</th></tr>");
                String sql = """
                  SELECT s.id, b.name as bus_name, r.source, r.destination, r.fare, s.date, s.time,
                         (b.capacity - (SELECT COUNT(*) FROM bookings bk WHERE bk.schedule_id=s.id AND bk.status!='CANCELLED')) AS avail
                  FROM schedules s
                  JOIN buses b ON b.id=s.bus_id
                  JOIN routes r ON r.id=s.route_id
                  WHERE (?='' OR r.source LIKE ?) AND (?='' OR r.destination LIKE ?) AND (?='' OR s.date=?)
                  ORDER BY s.date, s.time
                """;
                try (Connection c = DriverManager.getConnection(DB_URL);
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, src);
                    ps.setString(2, "%"+src+"%");
                    ps.setString(3, dst);
                    ps.setString(4, "%"+dst+"%");
                    ps.setString(5, date);
                    ps.setString(6, date);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()){
                            int id = rs.getInt("id");
                            int avail = rs.getInt("avail");
                            sb.append("<tr><td>").append(esc(rs.getString("bus_name"))).append("</td><td>")
                                    .append(esc(rs.getString("source")+" → "+rs.getString("destination")))
                                    .append("</td><td>").append(esc(rs.getString("date"))).append("</td><td>").append(esc(rs.getString("time")))
                                    .append("</td><td>").append(avail).append("</td><td>").append(rs.getDouble("fare"))
                                    .append("</td><td>");
                            if (avail>0) {
                                sb.append("<a class='btn ok' href='/user/book?scheduleId=").append(id).append("'>বুক</a>");
                            } else {
                                sb.append("<span class='pill'>পূর্ণ</span>");
                            }
                            sb.append("</td></tr>");
                        }
                    }
                } catch (SQLException e) { sb.append("<tr><td colspan='7'>").append(esc(e.getMessage())).append("</td></tr>"); }
                sb.append("</table></div>").append(pageFooter());
                sendHtml(ex,200,sb.toString());
            }
        }
    }
    static class BookHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Session s = getSession(ex);
            Map<String,String> q = parseQuery(ex.getRequestURI().getQuery());
            int scheduleId = Integer.parseInt(q.getOrDefault("scheduleId","0"));

            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String sql = """
                  SELECT s.id, b.name as bus_name, r.source, r.destination, r.fare, s.date, s.time,
                         (b.capacity - (SELECT COUNT(*) FROM bookings bk WHERE bk.schedule_id=s.id AND bk.status!='CANCELLED')) AS avail,
                         b.capacity as cap
                  FROM schedules s
                  JOIN buses b ON b.id=s.bus_id
                  JOIN routes r ON r.id=s.route_id
                  WHERE s.id=?
                """;
                try (Connection c = DriverManager.getConnection(DB_URL);
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, scheduleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { sendHtml(ex,200,"<p>সিডিউল পাওয়া যায়নি. <a class='link' href='/user'>ফিরে যান</a></p>"); return; }
                        String html = pageHeader("বুকিং", rs.getString("source")+" → "+rs.getString("destination"), s) +
                                "<div class='topbar'><a class='btn secondary' href='/user'>হোম</a></div>" +
                                "<div class='card'><div class='grid'>" +
                                "<div><h3>সিডিউল</h3><div class='pill'>বাস: "+esc(rs.getString("bus_name"))+"</div> " +
                                "<div class='pill'>তারিখ: "+esc(rs.getString("date"))+"</div> " +
                                "<div class='pill'>সময়: "+esc(rs.getString("time"))+"</div> " +
                                "<div class='pill'>উপলব্ধ: "+rs.getInt("avail")+"/"+rs.getInt("cap")+"</div> " +
                                "<div class='pill'>ভাড়া: "+rs.getDouble("fare")+"</div></div>" +
                                "<div><h3>তথ্য দিন</h3>" +
                                "<form method='POST' action='/user/book?scheduleId="+scheduleId+"'>" +
                                "<label>নাম</label><input name='name' required>" +
                                "<label>ফোন</label><input name='phone' required>" +
                                "<label>সিট নম্বর</label><input type='number' name='seat' min='1' max='"+rs.getInt("cap")+"' required>" +
                                "<button class='btn ok' style='margin-top:10px'>বুক করুন</button></form></div>" +
                                "</div></div>" + pageFooter();
                        sendHtml(ex,200,html);
                    }
                } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); }
                return;
            }

            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                Map<String,String> f = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String name = f.getOrDefault("name","").trim();
                String phone = f.getOrDefault("phone","").trim();
                int seat = Integer.parseInt(f.getOrDefault("seat","0"));
                if (name.isEmpty() || phone.isEmpty() || seat<=0){ sendHtml(ex,200,"<p>ডেটা সঠিক নয়. <a class='link' href='/user'>ফিরে যান</a></p>"); return; }

                String infoSql = """
                  SELECT r.fare, b.capacity,
                         (SELECT COUNT(*) FROM bookings bk WHERE bk.schedule_id=? AND bk.status!='CANCELLED') as booked
                  FROM schedules s
                  JOIN routes r ON r.id=s.route_id
                  JOIN buses b ON b.id=s.bus_id
                  WHERE s.id=?
                """;
                try (Connection c = DriverManager.getConnection(DB_URL)) {
                    double fare=0; int cap=0; int booked=0;
                    try (PreparedStatement ps = c.prepareStatement(infoSql)) {
                        ps.setInt(1, scheduleId); ps.setInt(2, scheduleId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()){ sendHtml(ex,200,"<p>সিডিউল পাওয়া যায়নি.</p>"); return; }
                            fare = rs.getDouble("fare"); cap = rs.getInt("capacity"); booked = rs.getInt("booked");
                        }
                    }
                    if (seat<1 || seat>cap) { sendHtml(ex,200,"<p>সিট রেঞ্জ সঠিক নয়.</p>"); return; }
                    if (booked>=cap) { sendHtml(ex,200,"<p>সিট পূর্ণ.</p>"); return; }

                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO bookings(name,phone,schedule_id,seat_no,status,total) VALUES(?,?,?,?,?,?)"
                    )) {
                        ps.setString(1,name); ps.setString(2,phone); ps.setInt(3,scheduleId);
                        ps.setInt(4,seat); ps.setString(5,"CONFIRMED"); ps.setDouble(6,fare);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (e.getMessage()!=null && e.getMessage().toLowerCase().contains("unique")) {
                            sendHtml(ex,200,"<p>এই সিট ইতিমধ্যে বুকড. <a class='link' href='/user'>ফিরে যান</a></p>");
                            return;
                        }
                        throw e;
                    }
                } catch (SQLException e) { sendHtml(ex,500,"DB error: "+esc(e.getMessage())); return; }

                String html = pageHeader("বুকিং সম্পন্ন","ধন্যবাদ!", s) +
                        "<div class='card'><a class='btn' href='/user'>হোম</a></div>" + pageFooter();
                sendHtml(ex,200,html);
            }
        }
    }
}
