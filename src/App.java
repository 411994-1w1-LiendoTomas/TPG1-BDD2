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
        cargarUsuariosIniciales();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/login", App::handleLogin);
        server.createContext("/api/productos", App::handleProductos);
        server.createContext("/api/reportes", App::handleReportes);
        server.createContext("/app.js", App::handleJs);
        server.createContext("/", App::handleStatic);
        server.start();

        System.out.println("=== TiendaTech corriendo en http://localhost:8080 ===");
        System.out.println("Presiona Ctrl+C para detener.");
    }

    static void handleJs(HttpExchange ex) throws IOException {
        addCors(ex);
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        File f = new File("src/app.js");
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void handleLogin(HttpExchange ex) throws IOException {
        addCors(ex);
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (ex.getRequestMethod().equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
            Map<String, String> data = parseJson(body);
            String username = data.get("username");
            String password = data.get("password");

            String key = "usuario:" + username;
            if (jedis.exists(key)) {
                String storedPassword = jedis.hget(key, "password");
                String rol = jedis.hget(key, "rol");
                if (password.equals(storedPassword)) {
                    respond(ex, 200, "{\"ok\":true,\"rol\":\"" + rol + "\",\"username\":\"" + username + "\"}");
                    return;
                }
            }
            respond(ex, 401, "{\"ok\":false,\"error\":\"Credenciales inválidas\"}");
        }
    }

    static String getRol(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            int colonIndex = token.indexOf(':');
            if (colonIndex > 0) {
                return token.substring(0, colonIndex);
            }
            return token;
        }
        return null;
    }

    static boolean tienePermiso(String rol, String metodo, String accion) {
        if (rol == null) return false;
        switch (rol) {
            case "ADMIN":
                return true;
            case "MANAGER":
                return !accion.equals("delete");
            case "EMPLOYEE":
                return metodo.equals("GET");
            default:
                return false;
        }
    }

    static void handleReportes(HttpExchange ex) throws IOException {
        addCors(ex);
        String rol = getRol(ex);
        if (rol == null) {
            respond(ex, 401, "{\"error\":\"No autenticado\"}");
            return;
        }
        if (!rol.equals("ADMIN")) {
            respond(ex, 403, "{\"error\":\"Solo ADMIN puede ver reportes\"}");
            return;
        }
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (ex.getRequestMethod().equals("GET")) {
            int activos = 0;
            int discontinuados = 0;
            int stockTotal = 0;
            int valorTotal = 0;
            for (String key : jedis.keys("producto:*")) {
                Map<String, String> p = jedis.hgetAll(key);
                if ("true".equals(p.getOrDefault("activo", "true"))) {
                    activos++;
                    stockTotal += Integer.parseInt(p.getOrDefault("stock", "0"));
                    valorTotal += Integer.parseInt(p.getOrDefault("precio", "0"));
                } else {
                    discontinuados++;
                }
            }
            String json = "{\"activos\":" + activos + ",\"discontinuados\":" + discontinuados + ",\"stockTotal\":" + stockTotal + ",\"valorTotal\":" + valorTotal + "}";
            respond(ex, 200, json);
        }
    }

    static void handleProductos(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String rol = getRol(ex);
        addCors(ex);

        if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

        if (method.equals("GET")) {
            getProductos(ex);
            return;
        }
        if (method.equals("POST")) {
            if (!tienePermiso(rol, "POST", "create")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para agregar productos\"}");
                return;
            }
            postProducto(ex);
            return;
        }
        if (method.equals("PUT")) {
            if (!tienePermiso(rol, "PUT", "update")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para modificar productos\"}");
                return;
            }
            putProducto(ex, path);
            return;
        }
        if (method.equals("DELETE")) {
            if (!tienePermiso(rol, "DELETE", "delete")) {
                respond(ex, 403, "{\"error\":\"No tienes permiso para borrar productos\"}");
                return;
            }
            deleteProducto(ex, path);
            return;
        }
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        addCors(ex);
        File f = new File("src/index.html");
        FileInputStream is = new FileInputStream(f);
        byte[] bytes = is.readAllBytes();
        is.close();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static void getProductos(HttpExchange ex) throws IOException {
        String activo = ex.getRequestHeaders().getFirst("X-Filtro-Activo");
        Set<String> keys = jedis.keys("producto:*");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : keys) {
            Map<String, String> p = jedis.hgetAll(key);
            if (p.isEmpty()) continue;
            String prodActivo = p.getOrDefault("activo", "true");
            if (activo != null) {
                if (!activo.equals(prodActivo)) continue;
            } else {
                if (!"true".equals(prodActivo)) continue;
            }
            if (!first) sb.append(",");
            first = false;
            sb.append(toJson(key, p));
        }
        sb.append("]");
        respond(ex, 200, sb.toString());
    }

    static void postProducto(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> data = parseJson(body);
        String key = "producto:" + nextId++;
        jedis.hset(key, "nombre", data.getOrDefault("nombre", ""));
        jedis.hset(key, "categoria", data.getOrDefault("categoria", ""));
        jedis.hset(key, "marca", data.getOrDefault("marca", ""));
        jedis.hset(key, "precio", data.getOrDefault("precio", "0"));
        jedis.hset(key, "stock", data.getOrDefault("stock", "0"));
        respond(ex, 201, "{\"ok\":true,\"key\":\"" + key + "\"}");
    }

    static void putProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
        Map<String, String> data = parseJson(body);
        if (data.containsKey("stock")) {
            jedis.hset(key, "stock", data.get("stock"));
        } else {
            for (String k : data.keySet()) {
                jedis.hset(key, k, data.get(k));
            }
        }
        respond(ex, 200, "{\"ok\":true}");
    }

    static void deleteProducto(HttpExchange ex, String path) throws IOException {
        String key = path.replace("/api/productos/", "producto:");
        jedis.hset(key, "activo", "false");
        respond(ex, 200, "{\"ok\":true}");
    }

    static void cargarDatosIniciales() {
        if (!jedis.keys("producto:*").isEmpty()) {
            System.out.println("Base de datos ya tiene datos, no se recarga.");
            for (String k : jedis.keys("producto:*")) {
                int id = Integer.parseInt(k.split(":")[1]);
                if (id >= nextId) nextId = id + 1;
            }
            return;
        }
        System.out.println("Cargando datos iniciales...");
        insertarProducto("Notebook Lenovo IdeaPad 3", "Notebooks", "Lenovo", "800000", "5");
        insertarProducto("Samsung Galaxy S23", "Celulares", "Samsung", "1200000", "8");
        insertarProducto("Auriculares Bluetooth Sony", "Audio", "Sony", "50000", "15");
        insertarProducto("Mouse Gamer Logitech", "Perifericos", "Logitech", "25000", "20");
        insertarProducto("Smart TV LG 50 pulgadas", "Televisores", "LG", "600000", "4");
        insertarProducto("Teclado Mecanico Redragon", "Perifericos", "Redragon", "70000", "10");
        insertarProducto("Tablet Samsung Galaxy Tab A8", "Tablets", "Samsung", "300000", "7");
        insertarProducto("Monitor LG 24 pulgadas", "Monitores", "LG", "200000", "6");
        System.out.println("8 productos cargados.");
    }

    static void cargarUsuariosIniciales() {
        if (jedis.exists("usuario:admin")) {
            System.out.println("Usuarios ya existen.");
            return;
        }
        System.out.println("Cargando usuarios...");
        jedis.hset("usuario:admin", "password", "admin123");
        jedis.hset("usuario:admin", "rol", "ADMIN");
        jedis.hset("usuario:manager", "password", "manager123");
        jedis.hset("usuario:manager", "rol", "MANAGER");
        jedis.hset("usuario:employee", "password", "employee123");
        jedis.hset("usuario:employee", "rol", "EMPLOYEE");
        System.out.println("Usuarios cargados: admin, manager, employee");
    }

    static void insertarProducto(String nombre, String cat, String marca, String precio, String stock) {
        String key = "producto:" + nextId++;
        jedis.hset(key, "nombre", nombre);
        jedis.hset(key, "categoria", cat);
        jedis.hset(key, "marca", marca);
        jedis.hset(key, "precio", precio);
        jedis.hset(key, "stock", stock);
        jedis.hset(key, "activo", "true");
    }

    static String toJson(String key, Map<String, String> p) {
        String id = key.split(":")[1];
        return "{\"id\":\"" + id + "\"," +
                "\"nombre\":\"" + esc(p.getOrDefault("nombre", "")) + "\"," +
                "\"categoria\":\"" + esc(p.getOrDefault("categoria", "")) + "\"," +
                "\"marca\":\"" + esc(p.getOrDefault("marca", "")) + "\"," +
                "\"precio\":" + p.getOrDefault("precio", "0") + "," +
                "\"stock\":" + p.getOrDefault("stock", "0") + "," +
                "\"activo\":\"" + p.getOrDefault("activo", "true") + "\"}";
    }

    static Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
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

    static String esc(String s) {
        return s.replace("\"", "\\\"");
    }

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
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Filtro-Activo");
    }

}