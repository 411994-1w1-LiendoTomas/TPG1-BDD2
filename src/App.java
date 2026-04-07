import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import redis.clients.jedis.Jedis;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class App {

    static Jedis jedis = new Jedis("localhost", 6379);
    static int nextId = 1;

    public static void main(String[] args) throws Exception {
        cargarDatosIniciales();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/productos", App::handleProductos);
        server.createContext("/app.js", App::handleJs);
        server.createContext("/", App::handleStatic);
        server.start();

        System.out.println("=== TiendaTech corriendo en http://localhost:8080 ===");
        System.out.println("Presiona Ctrl+C para detener.");
    }

    static void handleJs(HttpExchange ex) throws IOException {
    addCors(ex);
    File f = new File("src/app.js");
    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
    ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
    ex.sendResponseHeaders(200, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.getResponseBody().close();
    }

    // ── Rutas ──────────────────────────────────────────────

    static void handleProductos(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path   = ex.getRequestURI().getPath();
        addCors(ex);

        if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

        if (method.equals("GET"))    { getProductos(ex); return; }
        if (method.equals("POST"))   { postProducto(ex); return; }
        if (method.equals("PUT"))    { putProducto(ex, path); return; }
        if (method.equals("DELETE")) { deleteProducto(ex, path); return; }
    }

    static void handleStatic(HttpExchange ex) throws IOException {
    addCors(ex);
    InputStream is = App.class.getResourceAsStream("/index.html");
    if (is == null) {
        // buscar en src/
        File f = new File("src/index.html");
        is = new FileInputStream(f);
    }
    byte[] bytes = is.readAllBytes();
    is.close();
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
    ex.sendResponseHeaders(200, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.getResponseBody().close();
    }

    // ── CRUD ───────────────────────────────────────────────

    static void getProductos(HttpExchange ex) throws IOException {
        Set<String> keys = jedis.keys("producto:*");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : keys) {
            Map<String,String> p = jedis.hgetAll(key);
            if (p.isEmpty()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append(toJson(key, p));
        }
        sb.append("]");
        respond(ex, 200, sb.toString());
    }

    static void postProducto(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String,String> data = parseJson(body);
        String key = "producto:" + nextId++;
        jedis.hset(key, data);
        respond(ex, 201, "{\"ok\":true,\"key\":\"" + key + "\"}");
    }

    static void putProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String,String> data = parseJson(body);
        jedis.hset(key, data);
        respond(ex, 200, "{\"ok\":true}");
    }

    static void deleteProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        jedis.del(key);
        respond(ex, 200, "{\"ok\":true}");
    }

    // ── Datos iniciales ────────────────────────────────────

    static void cargarDatosIniciales() {
        if (!jedis.keys("producto:*").isEmpty()) {
            System.out.println("Base de datos ya tiene datos, no se recarga.");
            // calcular nextId
            for (String k : jedis.keys("producto:*")) {
                int id = Integer.parseInt(k.split(":")[1]);
                if (id >= nextId) nextId = id + 1;
            }
            return;
        }
        System.out.println("Cargando datos iniciales...");
        insertarProducto("Notebook Lenovo IdeaPad 3", "Notebooks", "Lenovo", "800000", "5");
        insertarProducto("Samsung Galaxy S23",         "Celulares",  "Samsung","1200000","8");
        insertarProducto("Auriculares Bluetooth Sony", "Audio",      "Sony",   "50000",  "15");
        insertarProducto("Mouse Gamer Logitech",       "Perifericos","Logitech","25000", "20");
        insertarProducto("Smart TV LG 50 pulgadas",    "Televisores","LG",     "600000", "4");
        insertarProducto("Teclado Mecanico Redragon",  "Perifericos","Redragon","70000", "10");
        insertarProducto("Tablet Samsung Galaxy Tab A8","Tablets",   "Samsung","300000", "7");
        insertarProducto("Monitor LG 24 pulgadas",     "Monitores",  "LG",     "200000", "6");
        System.out.println("8 productos cargados.");
    }

    static void insertarProducto(String nombre, String cat, String marca, String precio, String stock) {
        String key = "producto:" + nextId++;
        jedis.hset(key, Map.of(
            "nombre", nombre, "categoria", cat,
            "marca", marca, "precio", precio, "stock", stock
        ));
    }

    // ── Helpers ────────────────────────────────────────────

    static String toJson(String key, Map<String,String> p) {
        String id = key.split(":")[1];
        return "{\"id\":\"" + id + "\"," +
               "\"nombre\":\"" + esc(p.getOrDefault("nombre","")) + "\"," +
               "\"categoria\":\"" + esc(p.getOrDefault("categoria","")) + "\"," +
               "\"marca\":\"" + esc(p.getOrDefault("marca","")) + "\"," +
               "\"precio\":" + p.getOrDefault("precio","0") + "," +
               "\"stock\":" + p.getOrDefault("stock","0") + "}";
    }

    static Map<String,String> parseJson(String json) {
        Map<String,String> map = new HashMap<>();
        json = json.trim().replaceAll("[{}]", "");
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("\"", "");
                String v = kv[1].trim().replaceAll("\"", "");
                map.put(k, v);
            }
        }
        return map;
    }

    static String esc(String s) { return s.replace("\"", "\\\""); }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    
}